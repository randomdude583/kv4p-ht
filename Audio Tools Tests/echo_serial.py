import serial
import threading
import queue
import time
import sys

# Configuration Parameters
SERIAL_PORT = 'COM7'    # Replace with your serial port (e.g., 'COM3' on Windows or '/dev/ttyUSB0' on Linux/macOS)
BAUD_RATE = 460800      # Replace with your baud rate
READ_BUFFER_SIZE = 1024 # Size of chunks to read from serial
WRITE_BUFFER_SIZE = 1024# Size of chunks to write to serial
QUEUE_MAX_SIZE = 10000  # Maximum number of items in the queue to prevent memory issues

def read_from_port(ser, q):
    """Thread function to read data from serial port and put it into the queue."""
    while True:
        try:
            data = ser.read(READ_BUFFER_SIZE)
            if data:
                # Put data into queue without blocking; discard if queue is full
                try:
                    q.put_nowait(data)
                except queue.Full:
                    print("[WARNING] Write queue is full. Data is being dropped.")
        except serial.SerialException as e:
            print(f"[ERROR] Serial read error: {e}")
            break
        except Exception as e:
            print(f"[ERROR] Unexpected error in read thread: {e}")
            break

def write_to_port(ser, q):
    """Thread function to write data from the queue back to the serial port."""
    while True:
        try:
            # Retrieve data from queue; block if empty
            data = q.get()
            if data:
                ser.write(data)
                ser.flush()
        except serial.SerialException as e:
            print(f"[ERROR] Serial write error: {e}")
            break
        except Exception as e:
            print(f"[ERROR] Unexpected error in write thread: {e}")
            break

def main():
    try:
        # Initialize serial connection
        ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=0)
        print(f"[INFO] Opened serial port {SERIAL_PORT} at {BAUD_RATE} baud.")
    except serial.SerialException as e:
        print(f"[ERROR] Could not open serial port {SERIAL_PORT}: {e}")
        sys.exit(1)

    # Initialize queue with a maximum size to prevent unlimited memory usage
    q = queue.Queue(maxsize=QUEUE_MAX_SIZE)

    # Creating Threads for Reading and Writing
    reader_thread = threading.Thread(target=read_from_port, args=(ser, q), daemon=True)
    writer_thread = threading.Thread(target=write_to_port, args=(ser, q), daemon=True)

    reader_thread.start()
    writer_thread.start()

    print("[INFO] Echo started. Press Ctrl+C to stop.")

    try:
        while True:
            # Main thread can perform other tasks or simply wait
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[INFO] Echo stopped by user.")
    finally:
        ser.close()
        print(f"[INFO] Closed serial port {SERIAL_PORT}.")

if __name__ == "__main__":
    main()