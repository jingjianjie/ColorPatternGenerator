import pygame
import socket
import threading
import io
import time
import sys

# --- Configuration ---
TARGET_IP = "192.168.31.79"  # Change to your device IP
TCP_PORT = 8889
WINDOW_WIDTH = 540  # Initial window size (should match stream aspect ratio)
WINDOW_HEIGHT = 960

# --- Constants ---
DOUBLE_CLICK_TIME = 0.3  # Seconds to detect double click

class RemoteDesktopClient:
    def __init__(self):
        pygame.init()
        self.screen = pygame.display.set_mode((WINDOW_WIDTH, WINDOW_HEIGHT), pygame.RESIZABLE)
        pygame.display.set_caption("ColorPatternGenerator - Remote Desktop")
        
        self.sock = None
        self.connected = False
        self.running = True
        self.clock = pygame.time.Clock()
        
        self.current_frame = None
        self.frame_lock = threading.Lock()
        
        # Mouse State
        self.is_dragging = False
        self.last_mouse_pos = (0, 0)
        self.drag_start_pos = (0, 0)
        self.last_click_time = 0
        self.click_count = 0
        self.drag_start_time = 0
        
        # Display scaling
        self.scale_x = 1.0
        self.scale_y = 1.0
        self.offset_x = 0
        self.offset_y = 0

    def connect(self):
        try:
            print(f"[*] Connecting to {TARGET_IP}:{TCP_PORT}...")
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(5.0)
            self.sock.connect((TARGET_IP, TCP_PORT))
            self.connected = True
            print("[+] Connected!")
            
            # Start stream thread
            t = threading.Thread(target=self._stream_receiver, daemon=True)
            t.start()
            
            # Handshake
            self.send_cmd("rd-hello")
            
        except Exception as e:
            print(f"[-] Connection failed: {e}")
            self.connected = False

    def send_cmd(self, cmd):
        if not self.connected: return
        try:
            # print(f"Sending: {cmd}")
            self.sock.sendall(cmd.encode())
        except Exception as e:
            print(f"[-] Send error: {e}")
            self.connected = False

    def _stream_receiver(self):
        while self.connected and self.running:
            try:
                # Read header "frame:SIZE\n"
                header = b""
                while b"\n" not in header:
                    chunk = self.sock.recv(1)
                    if not chunk: 
                        self.connected = False
                        return
                    header += chunk
                
                line = header.decode().strip()
                if line.startswith("frame:"):
                    size = int(line.split(":")[1])
                    
                    # Read image data
                    data = b""
                    while len(data) < size:
                        chunk = self.sock.recv(size - len(data))
                        if not chunk: return
                        data += chunk
                        
                    # Decode image
                    image_stream = io.BytesIO(data)
                    try:
                        image = pygame.image.load(image_stream)
                        with self.frame_lock:
                            self.current_frame = image
                    except pygame.error:
                        pass
                        
            except Exception as e:
                print(f"[-] Stream error: {e}")
                self.connected = False
                break

    def _map_coordinates(self, pos):
        """Map window coordinates to device coordinates"""
        # Remove offset
        x = pos[0] - self.offset_x
        y = pos[1] - self.offset_y
        
        # Apply scaling
        dev_x = x / self.scale_x
        dev_y = y / self.scale_y
        
        # Ensure within bounds (assuming 540x960 device resolution)
        # Ideally we should get this from handshake
        # For now, clamp to 0..540, 0..960
        dev_x = max(0, min(dev_x, 540))
        dev_y = max(0, min(dev_y, 960))
        
        return int(dev_x), int(dev_y)

    def _update_display_metrics(self):
        """Calculate scaling and centering for the frame"""
        if self.current_frame:
            frame_w = self.current_frame.get_width()
            frame_h = self.current_frame.get_height()
            
            win_w, win_h = self.screen.get_size()
            
            # Fit to window maintaining aspect ratio
            scale_w = win_w / frame_w
            scale_h = win_h / frame_h
            scale = min(scale_w, scale_h)
            
            self.scale_x = scale
            self.scale_y = scale
            
            disp_w = int(frame_w * scale)
            disp_h = int(frame_h * scale)
            
            self.offset_x = (win_w - disp_w) // 2
            self.offset_y = (win_h - disp_h) // 2
            
            return pygame.transform.scale(self.current_frame, (disp_w, disp_h))
        return None

    def run(self):
        if not self.connect():
            return

        while self.running:
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    self.running = False
                    
                elif event.type == pygame.KEYDOWN:
                    if event.key == pygame.K_ESCAPE:
                        self.send_cmd("c-key:2") # Back
                    elif event.key == pygame.K_HOME:
                        self.send_cmd("c-key:1") # Home
                    elif event.key == pygame.K_TAB: # Alt-Tab -> Recents
                        self.send_cmd("c-key:3") # Recents
                        
                elif event.type == pygame.MOUSEBUTTONDOWN:
                    if event.button == 1: # Left Click
                        self.is_dragging = True
                        self.drag_start_pos = event.pos
                        self.drag_start_time = time.time()
                        
                        # Double Click Detection
                        if time.time() - self.last_click_time < DOUBLE_CLICK_TIME:
                            # It's a double click! 
                            # But wait, standard Android doesn't have "double click" event.
                            # It's just two taps. We send them as taps.
                            pass
                        self.last_click_time = time.time()

                elif event.type == pygame.MOUSEBUTTONUP:
                    if event.button == 1:
                        self.is_dragging = False
                        end_pos = event.pos
                        duration = (time.time() - self.drag_start_time) * 1000 # ms
                        
                        start_x, start_y = self._map_coordinates(self.drag_start_pos)
                        end_x, end_y = self._map_coordinates(end_pos)
                        
                        # Threshold for Swipe vs Tap
                        dist = ((end_pos[0]-self.drag_start_pos[0])**2 + (end_pos[1]-self.drag_start_pos[1])**2)**0.5
                        
                        if dist < 10: # It's a Tap
                            self.send_cmd(f"c-tap:{start_x},{start_y}")
                        else: # It's a Swipe
                            # Duration needs to be reasonable
                            if duration < 50: duration = 50
                            self.send_cmd(f"c-swipe:{start_x},{start_y},{end_x},{end_y},{int(duration)}")

                elif event.type == pygame.MOUSEMOTION:
                    if self.is_dragging:
                        # We don't send real-time drag updates because protocol is atomic swipe
                        # But we could visualize the drag line on screen
                        pass

            # Render
            self.screen.fill((0, 0, 0))
            
            with self.frame_lock:
                scaled_surface = self._update_display_metrics()
                if scaled_surface:
                    self.screen.blit(scaled_surface, (self.offset_x, self.offset_y))
                else:
                    # Show "Waiting for stream..." text
                    font = pygame.font.SysFont(None, 36)
                    text = font.render("Waiting for stream...", True, (255, 255, 255))
                    self.screen.blit(text, (WINDOW_WIDTH//2 - 100, WINDOW_HEIGHT//2))
            
            # Draw drag line if dragging
            if self.is_dragging:
                pygame.draw.line(self.screen, (0, 255, 0), self.drag_start_pos, pygame.mouse.get_pos(), 2)

            pygame.display.flip()
            self.clock.tick(30) # UI Frame rate

        if self.sock:
            self.sock.close()
        pygame.quit()
        sys.exit()

if __name__ == "__main__":
    client = RemoteDesktopClient()
    client.run()