import socket
import time
import threading

# Config
UDP_PORT = 9999
TCP_PORT = 8888  # Local port to listen on
MY_IP = "192.168.31.11" # Update this to your PC's IP if needed, or use 0.0.0.0

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
    
    # Get local IP automatically for the handshake message
    # This might need adjustment depending on your network interface
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except:
        local_ip = "192.168.1.100" # Fallback

    msg = f"hello_pg_0927:{local_ip}:{TCP_PORT}"
    print(f"[*] Broadcasting Handshake: {msg}")
    
    # Broadcast to 255.255.255.255
    sock.sendto(msg.encode(), ('<broadcast>', UDP_PORT))
    sock.close()

def main():
    # 1. Start TCP Server in a thread (or just wait, but handshake needs to happen)
    # We'll just listen first, then broadcast.
    
    server_thread = threading.Thread(target=start_tcp_server)
    
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(('0.0.0.0', TCP_PORT))
    server_socket.listen(1)
    
    print(f"[*] Waiting for connection on port {TCP_PORT}...")
    
    # Send handshake repeatedly until connected
    connected = False
    conn = None
    
    def wait_for_conn():
        nonlocal conn, connected
        conn, addr = server_socket.accept()
        print(f"[+] Device connected: {addr}")
        connected = True

    t = threading.Thread(target=wait_for_conn)
    t.start()
    
    while not connected:
        send_udp_handshake()
        time.sleep(2)
    
    # 2. Test Sequence
    try:
        # Clear buffer
        conn.settimeout(0.1)
        try:
            while True: conn.recv(1024)
        except: pass
        conn.settimeout(None)

        print("\n[1] Sending Color Pattern (Red)...")
        conn.sendall(b"color=255,0,0\n")
        time.sleep(2)

        print("\n[2] Sending Circle Pattern (Green)...")
        conn.sendall(b"color_o=0,255,0\n")
        time.sleep(2)

        print("\n[3] Testing set_position (Enter Position Mode)...")
        conn.sendall(b"set_position\n")
        
        # Read angles loop
        print("Reading angles (Ctrl+C to stop)...")
        while True:
            conn.sendall(b"get_position_angle\n")
            data = conn.recv(1024).decode().strip()
            print(f"Angle: {data}")
            time.sleep(0.2)


    except Exception as e:
        print(f"Error: {e}")
    finally:
        if conn: conn.close()
        server_socket.close()
        print("[*] Done")

if __name__ == "__main__":
    main()
