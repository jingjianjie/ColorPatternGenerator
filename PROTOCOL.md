# ColorPatternGenerator 通信协议文档 (v2.4)

## 1. 概述 (Overview)
本应用旨在提供一个可通过网络远程控制的屏幕测试与校准工具。支持 **UDP** 和 **TCP** 两种通信模式。

*   **UDP 模式**: 仅用于设备发现和握手。**无论是否存在 TCP 连接，UDP 控制指令都将被忽略。**
*   **TCP 模式**: 建立稳定连接后，支持全功能控制（屏幕校准、图片传输、摄像头推流、姿态校准）。支持多客户端并发连接。

**默认监听端口**:
*   UDP: 9999
*   TCP: 建立连接后由 Client 发起

---

## 2. UDP 协议 (UDP Protocol)
应用启动后，会默认监听 UDP 9999 端口。

在复杂网络中，如果底层 UDP `recvfrom` 返回 `ECONNABORTED` 或其他临时 socket 错误，App 会记录错误、关闭当前 UDP socket，并在约 1 秒后重新绑定 9999 端口继续监听。TCP 已建立连接不受该 UDP 监听重启影响。

### 2.1 握手指令 (Handshake)
用于通知 App 主动连接到控制端的 TCP Server。
- **格式**: `hello_pg_0927:<IP>:<PORT>`
- **参数**:
    - `IP`: 控制端 (PC/Server) 的 IP 地址
    - `PORT`: 控制端监听的 TCP 端口
- **示例**: `hello_pg_0927:192.168.1.100:8888`
- **行为**: App 收到此指令后，会启动新线程尝试连接该 TCP 地址。支持多个不同 IP 的客户端同时连接。若同一 IP 重复发送，会先断开旧连接。

### 2.2 非握手消息
UDP 不再兼容直接控制指令。`color=R,G,B`、`color_o=R,G,B`（含可选亮度参数）及其他非握手消息均会被忽略，无论是否存在 TCP 连接。这些消息不会改变显示图案或添加历史记录。显示控制请使用第 3 节的 TCP 协议。

---

## 3. TCP 协议 (TCP Protocol)
连接建立后，App 工作在 TCP Client 模式。指令以文本行 (`\n` 结尾) 发送。

### 3.1 屏幕显示控制

#### 基础显示
| 指令 | 说明 |
| :--- | :--- |
| `color=R,G,B` | 全屏显示纯色 |
| `color=R,G,B,Brightness` | 全屏显示纯色并设置屏幕亮度 (0.01-100.0) |
| `color_o=R,G,B` | 显示圆形 Pattern (默认直径 20mm) |
| `color_o=R,G,B,Brightness` | 显示圆形 Pattern 并设置屏幕亮度 (0.01-100.0) |

#### 圆盘 Pattern 配置
| 指令 | 说明 | 响应 |
| :--- | :--- | :--- |
| `get_disk_property` | 获取当前圆形 Pattern 的属性 | `Diameter(mm),CenterX(mm),CenterY(mm)\n` |
| `set_disk_property:D,X,Y` | 设置属性。<br>`D`: 直径(mm)<br>`X`: 忽略(始终居中)<br>`Y`: 绝对Y坐标(mm) | `Diameter,CenterX,CenterY\n` (设置后的值) |
| `reset_pos` | 重置圆盘位置到中心，直径到 20mm | - |

#### 图片传输
发送一张图片并在 App 上显示。
1. **发送头**: `image:<filename>:<size>\n`
2. **发送数据**: `size` 字节的图片二进制数据。

### 3.2 状态与姿态 (Status & Position)

#### 状态查询
| 指令 | 说明 | 响应 |
| :--- | :--- | :--- |
| `get_status` | 获取设备综合状态 | `PatternName,ColorInfo,Pitch,Yaw,DiskDiameterMm,DiskPosX,DiskPosY,Brightness\n` |
| | | 示例: `Disk,0,255,0,45.12,-10.05,20.00,0.00,50.00,100.00` |

**响应字段说明**:
1.  **PatternName**: 当前模式 (`FullColor`, `Disk`, `Image:Name`, `CameraStream`, `PositionMode`, `None`)
2.  **ColorInfo**: 当前 RGB 颜色 (`R,G,B`) 或 `N/A`
3.  **Pitch**: 俯仰角 (度)
4.  **Yaw**: 横滚角 (度)
5.  **DiskDiameterMm**: 圆盘直径 (mm)
6.  **DiskPosX**: 圆盘X位置偏移 (始终为 0.00)
7.  **DiskPosY**: 圆盘Y位置偏移百分比 (-100.00% 到 100.00%, 0为中心)
8.  **Brightness**: 当前屏幕亮度 (0.00 - 100.00)

#### 姿态校准模式
| 指令 | 说明 | 响应 |
| :--- | :--- | :--- |
| `set_position` | **进入姿态模式**。<br>显示重力十字线（绿色动态线指向物理下方）。 | 无 |
| `get_position_angle` | 获取当前设备姿态（建议 33ms 间隔查询）。 | `Pitch,Yaw\n` (单位：度，保留2位小数) |
| *(任意其他显示指令)* | 发送 `color=...` 等指令会自动**退出姿态模式**。 | - |

### 3.3 摄像头推流 (Side-Channel Streaming)
为了保证控制指令的实时性，推流数据将通过一个**独立协商的 TCP 端口**进行传输。

#### 推流流程
1.  **Client (Control)** -> 发送 `stream_start\n`
2.  **App** -> 启动内部 StreamServer，分配一个随机端口。
3.  **App** -> 响应 `stream_ready:<Port>\n` (例如 `stream_ready:34567`)
4.  **Client (Data)** -> 发起新的 TCP 连接到 App IP 的 `<Port>`。
5.  **App** -> 接受连接，并开始在新连接上发送 JPEG 帧数据。

| 指令 | 说明 | 响应 |
| :--- | :--- | :--- |
| `stream_start` | **请求推流**。<br>开启独立数据端口。 | `stream_ready:<Port>\n` |
| `stream_stop` | **停止推流**。<br>仅启动推流的控制连接可以停止当前推流；该控制连接断开时也会自动停止推流。App 将关闭数据端口并停止 CameraX 采集。 | - |

### 3.4 设备信息与电源管理
| 指令 | 说明 | 响应 |
| :--- | :--- | :--- |
| `basic config` | 获取屏幕物理参数 | `Width(mm),Height(mm),Brightness\n` |
| `sleep` | **远程伪休眠**。<br>保存当前图案和亮度，关闭屏幕背光(黑屏, 全屏黑色模式)，但保持 CPU 和网络连接活跃 (WakeLock + WifiLock)。重复发送不会覆盖第一次休眠前保存的图案。 | - |
| `wake` | **远程唤醒**。<br>恢复休眠前亮度和常亮状态，并**自动恢复**到休眠前的图案模式。 | - |
| `lock` | 锁定触摸手势。锁定后不响应手指操作，但仍响应网络控制。 | - |
| `unlock` | 解除触摸手势锁定。 | - |

### 3.5 远程桌面与输入控制 (Remote Desktop)
远程桌面是独立于 3.3 摄像头推流的辅助通道。App 启动后会在本机监听 TCP `8889`，用于屏幕帧传输和远程输入注入。

使用前置条件：
- 需要授予屏幕录制权限，才能发送屏幕帧。
- 需要启用 App 的无障碍服务，才能执行点击、滑动和全局导航键。
- 同一时间只保留一个远程桌面客户端，新客户端连接后会替换旧客户端。

#### 连接与帧格式
Client 连接到 App IP 的 `8889` 端口后，App 在有屏幕帧时发送：

```text
frame:<size>\n
<size bytes JPEG data>
```

#### 输入控制指令
Client 发送文本指令，格式如下：

| 指令 | 说明 |
| :--- | :--- |
| `rd-hello` | 心跳/保活，响应 `rd-ok:hello\n` |
| `rd-status` | 查询能力状态，响应 `rd-status:accessibility=<true/false>,screen_capture=<true/false>\n` |
| `c-tap:X,Y` | 在屏幕坐标 `(X,Y)` 执行一次点击 |
| `c-swipe:X1,Y1,X2,Y2,DurationMs` | 从 `(X1,Y1)` 滑动到 `(X2,Y2)`，持续 `DurationMs` 毫秒 |
| `c-key:1` | 执行 HOME |
| `c-key:2` | 执行 BACK |
| `c-key:3` | 执行 RECENTS |

输入控制响应：
- 成功：`rd-ok:<command>\n`
- 未启用无障碍服务：`rd-error:accessibility_disabled\n`
- 参数错误：`rd-error:bad_<command>\n`
- 未知命令：`rd-error:unknown_command\n`

说明：ADB 不是远程控制的运行必需条件；用户可以手动在系统设置中启用无障碍服务和屏幕录制授权。ADB 只适合调试、安装 APK、抓取 logcat，或在受控测试设备上辅助打开设置页面。

## 4. 手势交互行为 (Gestures)

### 4.1 历史控制 (History)
*   **Undo**: 单指 **水平向左** 滑动 (Swipe Left)。
*   **Redo**: 单指 **水平向右** 滑动 (Swipe Right)。
*   **过滤规则**:
    *   必须是单指操作。
    *   滑动持续时间必须 > 50ms (防止点击误触)。
    *   水平位移必须大于垂直位移的 2 倍 (防止垂直拖拽误触)。

### 4.2 圆盘控制 (Disk Mode)
*   **缩放 (Zoom)**: 双指捏合/张开。改变圆盘直径。
*   **移动 (Move)**: **双指** 上下拖拽。改变圆盘 Y 轴位置。
*   **重置/恢复 (Reset/Toggle)**: **双指双击** (Double Tap with 2 fingers)。
    *   若当前圆盘处于自定义状态，则**保存**当前状态并重置为中心 (20mm)。
    *   若当前圆盘处于默认状态，则**恢复**上一次保存的自定义状态。

### 4.3 触摸锁定 (Touch Lock)
*   **锁定/解锁**: 三指长按超过 1.0 秒切换锁定状态。
*   锁定后，App 不响应撤销/重做、圆盘缩放、圆盘拖动、双指双击等手指操作。
*   锁定状态下仍保留三指长按用于解锁，且网络指令仍正常执行。

---
