import serial
import time

# Replace 'COM3' with your serial port (e.g., '/dev/ttyUSB0' on Linux/macOS)
SERIAL_PORT = 'COM7'
BAUD_RATE = 300000
TIMEOUT = 1  # seconds

# The string you want to send
MESSAGE = "Hello, Microcontroller!\n"

try:
    # Initialize serial connection
    ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=TIMEOUT)
    print(f"Connected to {SERIAL_PORT} at {BAUD_RATE} baud.")

    # Give some time for the connection to initialize
    time.sleep(2)

    # Send the message (encode to bytes)
    ser.write(MESSAGE.encode('utf-8'))
    print(f"Sent: {MESSAGE}")

    time.sleep(1)

    # Wait for a response
    if ser.in_waiting > 0:
        response = ser.read(ser.in_waiting).decode('utf-8', errors='replace')
        print(f"Received: {response}")
    else:
        print("No response received.")

    # Close the serial connection
    ser.close()
    print("Serial connection closed.")

except serial.SerialException as e:
    print(f"Serial error: {e}")

except Exception as e:
    print(f"Unexpected error: {e}")