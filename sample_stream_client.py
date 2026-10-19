import socket
import time
import threading
import sys

# Config
UDP_PORT = 9999
TCP_PORT = 8888
TARGET_IP = "192.168.31.79" 

class Client:
    def __init__(self):
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind(('0.0.0.0', TCP_PORT))
        self.server_socket.listen(1)
        self.conn = None
        self.addr = None
        self.connected = False

    def wait_for_connection(self):
        print(f"[*] Waiting for connection on port {TCP_PORT}...")
        self.conn, self.addr = self.server_socket.accept()
        print(f"[+] Connected by {self.addr}")
        self.connected = True
        return self.conn

    def send_handshake(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            # Use local IP or 0.0.0.0? 
            # Better to use actual IP if possible
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            local_ip = s.getsockname()[0]
            s.close()
        except:
            local_ip = "192.168.31.11" # Fallback/Guess

        msg = f"hello_pg_0927:{local_ip}:{TCP_PORT}"
        print(f"[*] Sending Handshake to {TARGET_IP}: {msg}")
        sock.sendto(msg.encode(), (TARGET_IP, UDP_PORT))
        sock.close()

def connect_data_stream(ip, port):
    try:
        print(f"[Stream] Connecting to {ip}:{port}...")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5.0)
        sock.connect((ip, int(port)))
        print("[Stream] Connected! Receiving data...")
        
        frame_count = 0
        total_bytes = 0
        start_time = time.time()
        
        while True:
            # Read header line (e.g., "frame:12345\n")
            header = b""
            while b"\n" not in header:
                try:
                    chunk = sock.recv(1)
                    if not chunk: 
                        print("\n[Stream] Disconnected (EOF header)")
                        return
                    header += chunk
                except socket.timeout:
                    print("\n[Stream] Timeout waiting for header")
                    return
            
            line = header.decode().strip()
            if line.startswith("frame:"):
                try:
                    size = int(line.split(":")[1])
                    # Read size bytes
                    data = b""
                    while len(data) < size:
                        chunk = sock.recv(size - len(data))
                        if not chunk: 
                            print("\n[Stream] Disconnected (EOF data)")
                            return
                        data += chunk
                    
                    frame_count += 1
                    total_bytes += size
                    
                    # Calculate FPS/Bitrate
                    elapsed = time.time() - start_time
                    if elapsed > 1.0:
                        fps = frame_count / elapsed
                        kbps = (total_bytes * 8 / 1024) / elapsed
                        print(f"\r[Stream] FPS: {fps:.1f}, Bitrate: {kbps:.0f} kbps, Last Frame: {size} bytes   ", end="")
                        frame_count = 0
                        total_bytes = 0
                        start_time = time.time()
                        
                except ValueError:
                    print(f"\n[Stream] Bad header size: {line}")
            else:
                 print(f"\n[Stream] Invalid header: {line}")
                
    except Exception as e:
        print(f"\n[Stream] Error: {e}")
    finally:
        print("\n[Stream] Closed.")

def main():
    client = Client()
    
    # Thread to accept connection
    t = threading.Thread(target=client.wait_for_connection, daemon=True)
    t.start()
    
    # Loop handshake until connected
    while not client.connected:
        client.send_handshake()
        time.sleep(2)
        if client.connected: break
    
    conn = client.conn
    device_ip = client.addr[0]
    
    # Interaction
    try:
        print("\n[1] Requesting Stream (Side-Channel)...")
        conn.sendall(b"stream_start\n")
        
        # Wait for response "stream_ready:PORT"
        response = conn.recv(1024).decode().strip()
        print(f"[*] Response: {response}")
        
        if response.startswith("stream_ready:"):
            port = response.split(":")[1]
            # Start data receiver thread
            stream_thread = threading.Thread(target=connect_data_stream, args=(device_ip, port), daemon=True)
            stream_thread.start()
            
            print("[*] Stream negotiation successful. Press Ctrl+C to stop.")
            
            # Interactive Loop
            while True:
                print("\nCommands:")
                print("  sleep       - Remote sleep (black screen)")
                print("  wake        - Remote wake")
                print("  status      - Get status")
                print("  reset_pos   - Reset disk position")
                print("  color=R,G,B,[Bright] - Set color")
                print("  color_o=R,G,B,[Bright] - Set disk color")
                print("  q           - Quit")
                cmd = input("Command: ").strip()
                
                if cmd == 'q':
                    break
                elif cmd == 'sleep':
                    conn.sendall(b"sleep\n")
                elif cmd == 'wake':
                    conn.sendall(b"wake\n")
                elif cmd == 'reset_pos':
                    conn.sendall(b"reset_pos\n")
                elif cmd == 'status':
                    conn.sendall(b"get_status\n")
                    print("Sent.")
                elif cmd.startswith("color"):
                    conn.sendall(f"{cmd}\n".encode())
                else:
                    print("Unknown command")
                
        else:
            print("[!] Unexpected response.")

    except KeyboardInterrupt:
        print("\n\n[*] Stopping...")
        try:
            conn.sendall(b"stream_stop\n")
            time.sleep(0.5)
        except: pass
    except Exception as e:
        print(f"\nError: {e}")
    finally:
        if conn: conn.close()
        client.server_socket.close()

if __name__ == "__main__":
    main()
