import socket
import time
import threading
import sys

# Config
TCP_PORT = 8889 # Remote Desktop Port
TARGET_IP = "192.168.31.79" 

class RDClient:
    def __init__(self):
        self.sock = None
        self.connected = False

    def connect(self):
        try:
            print(f"[*] Connecting to RD Server {TARGET_IP}:{TCP_PORT}...")
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(5.0)
            self.sock.connect((TARGET_IP, TCP_PORT))
            print("[+] Connected!")
            self.connected = True
            return True
        except Exception as e:
            print(f"[-] Connection failed: {e}")
            return False

    def send_cmd(self, cmd):
        if not self.connected: return
        try:
            self.sock.sendall(cmd.encode())
        except Exception as e:
            print(f"[-] Send error: {e}")
            self.connected = False

    def start_stream_reader(self):
        t = threading.Thread(target=self._stream_loop, daemon=True)
        t.start()

    def _stream_loop(self):
        print("[*] Stream reader started")
        frame_count = 0
        total_bytes = 0
        start_time = time.time()
        
        while self.connected:
            try:
                # Read header "frame:SIZE\n"
                header = b""
                while b"\n" not in header:
                    chunk = self.sock.recv(1)
                    if not chunk: 
                        print("[-] Stream EOF")
                        self.connected = False
                        return
                    header += chunk
                
                line = header.decode().strip()
                if line.startswith("frame:"):
                    size = int(line.split(":")[1])
                    # Read body
                    data = b""
                    while len(data) < size:
                        chunk = self.sock.recv(size - len(data))
                        if not chunk: return
                        data += chunk
                    
                    frame_count += 1
                    total_bytes += size
                    
                    elapsed = time.time() - start_time
                    if elapsed > 1.0:
                        fps = frame_count / elapsed
                        kbps = (total_bytes * 8 / 1024) / elapsed
                        print(f"\r[RD Stream] FPS: {fps:.1f}, Bitrate: {kbps:.0f} kbps, Size: {size}   ", end="")
                        frame_count = 0
                        total_bytes = 0
                        start_time = time.time()
            except Exception as e:
                print(f"[-] Stream error: {e}")
                self.connected = False
                break

def main():
    client = RDClient()
    if not client.connect():
        return

    # Start stream reader
    client.start_stream_reader()
    
    # Handshake
    client.send_cmd("rd-hello")
    
    print("\nRemote Desktop Console")
    print("Commands:")
    print("  tap x y           - Click at x,y")
    print("  swipe x1 y1 x2 y2 ms - Swipe")
    print("  home / back / recents - Global keys")
    print("  q                 - Quit")
    
    try:
        while True:
            line = input("> ").strip()
            if line == 'q': break
            
            parts = line.split()
            if not parts: continue
            
            cmd = parts[0]
            if cmd == "tap" and len(parts) == 3:
                client.send_cmd(f"c-tap:{parts[1]},{parts[2]}")
            elif cmd == "swipe" and len(parts) == 6:
                client.send_cmd(f"c-swipe:{parts[1]},{parts[2]},{parts[3]},{parts[4]},{parts[5]}")
            elif cmd == "home":
                client.send_cmd("c-key:1")
            elif cmd == "back":
                client.send_cmd("c-key:2")
            elif cmd == "recents":
                client.send_cmd("c-key:3")
            else:
                print("Unknown command format")
                
    except KeyboardInterrupt:
        pass
    finally:
        if client.sock: client.sock.close()

if __name__ == "__main__":
    main()