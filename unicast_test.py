import socket
import time
import threading

TARGET_IP = "192.168.31.92"
TARGET_PORT = 9999
MY_PORT = 8888

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except:
        return "127.0.0.1"

MY_IP = get_local_ip()

def start_tcp_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('0.0.0.0', MY_PORT))
    server.listen(1)
    print(f"[*] TCP Server listening on {MY_IP}:{MY_PORT}")
    
    server.settimeout(10)
    try:
        conn, addr = server.accept()
        print(f"[SUCCESS] Phone connected back from {addr}!")
        conn.send(b"basic config\n")
        print("Sent 'basic config', waiting for response...")
        data = conn.recv(1024)
        print(f"Received: {data.decode()}")
        conn.close()
    except socket.timeout:
        print("[TIMEOUT] Phone did not connect back via TCP.")
    except Exception as e:
        print(f"[ERROR] TCP Server error: {e}")
    finally:
        server.close()

def send_unicast_handshake():
    # Wait a bit for TCP server to start
    time.sleep(1)
    
    msg = f"hello_pg_0927:{MY_IP}:{MY_PORT}"
    print(f"[*] Sending UNICAST UDP to {TARGET_IP}:{TARGET_PORT} -> {msg}")
    
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for i in range(3):
        sock.sendto(msg.encode(), (TARGET_IP, TARGET_PORT))
        print(f"    Sent packet {i+1}")
        time.sleep(1)
    sock.close()

if __name__ == "__main__":
    t = threading.Thread(target=start_tcp_server)
    t.start()
    
    send_unicast_handshake()
    t.join()
