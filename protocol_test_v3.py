import socket
import time
import threading
import sys

# Config
UDP_PORT = 9999
TCP_PORT = 8888
# TARGET_IP = "192.168.31.79" # Set this if known, otherwise discovery will find it

def start_tcp_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', TCP_PORT))
    server.listen(1)
    print(f"[*] TCP Server listening on 0.0.0.0:{TCP_PORT}")
    
    conn, addr = server.accept()
    print(f"[+] Connected by {addr}")
    return conn

def send_udp_handshake():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except:
        local_ip = "127.0.0.1"

    msg = f"hello_pg_0927:{local_ip}:{TCP_PORT}"
    print(f"[*] Broadcasting Handshake: {msg}")
    sock.sendto(msg.encode(), ('<broadcast>', UDP_PORT))
    sock.close()

def main():
    server_thread = threading.Thread(target=start_tcp_server, daemon=True)
    
    # Setup server socket in main thread to accept
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(('0.0.0.0', TCP_PORT))
    server_socket.listen(1)
    
    print(f"[*] Waiting for connection on port {TCP_PORT}...")
    
    # Handshake loop
    connected = False
    conn = None
    
    def wait_for_conn():
        nonlocal conn, connected
        try:
            conn, addr = server_socket.accept()
            print(f"[+] Device connected: {addr}")
            connected = True
        except: pass

    t = threading.Thread(target=wait_for_conn, daemon=True)
    t.start()
    
    while not connected:
        send_udp_handshake()
        time.sleep(2)
    
    # Interaction
    try:
        # 1. Enter Position Mode
        print("\n[1] Entering Position Mode...")
        conn.sendall(b"set_position\n")
        time.sleep(0.5)
        
        # 2. Loop reading angles
        print("\n[2] Reading Angles (Press Ctrl+C to stop)...")
        print("    Format expected: Pitch, Yaw")
        
        while True:
            conn.sendall(b"get_position_angle\n")
            data = conn.recv(1024).decode().strip()
            print(f"\rAngle Data: {data}        ", end="")
            time.sleep(0.1)

    except KeyboardInterrupt:
        print("\n\n[*] Stopping...")
        try:
            conn.sendall(b"color=0,0,0\n") # Exit mode
        except: pass
    except Exception as e:
        print(f"\nError: {e}")
    finally:
        if conn: conn.close()
        server_socket.close()

if __name__ == "__main__":
    main()
