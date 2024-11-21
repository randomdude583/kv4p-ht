import serial
import time
import sys
import threading
import msvcrt
import math

# ======================= Configuration =======================

# Replace 'COM7' with your actual COM port identified in Device Manager
SERIAL_PORT = 'COM7'

# Baud rate must match the one set in your Arduino code
BAUD_RATE = 921600

# Timeout for serial operations in seconds
TIMEOUT = 1

# Sine wave parameters
frequency = 300  # Hz
amplitude = 127  # 0-255
offset = 128  # Center the sine wave around 128
sampling_frequency = 44100  # Hz

# ===============================================================


def read_from_serial(ser):
    """
    Continuously read data from the serial port and print to console.
    """
    while True:
        try:
            if ser.in_waiting > 0:
                incoming = ser.read(ser.in_waiting)
                try:
                    # Attempt to decode as ASCII for readability
                    decoded = incoming.decode('ascii').strip()
                    if decoded:
                        print(f"Received: {decoded}")
                except UnicodeDecodeError:
                    # If data is not ASCII, print raw bytes
                    print(f"Received (raw): {incoming}")
            time.sleep(0.1)  # Adjust sleep duration as needed
        except serial.SerialException:
            print("Serial port closed.")
            break
        except Exception as e:
            print(f"Error reading from serial: {e}")
            break








def main():
    try:
        # Initialize serial connection
        ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=TIMEOUT)
        print(f"Opened serial port: {SERIAL_PORT} at {BAUD_RATE} baud.")
        time.sleep(2)  # Wait for the connection to initialize

        # Start the serial reading thread
        serial_thread = threading.Thread(target=read_from_serial, args=(ser,), daemon=True)
        serial_thread.start()








        # Main loop to listen for key presses
        # while True:
        #     if msvcrt.kbhit():
        #         key = msvcrt.getch().decode('utf-8')
        #         if key.lower() == 'q':
        #             print("Exiting program...")
        #             break
        #         else:
        #             message = "Hello, serial port!"
        #             if not message.endswith('\n'):
        #                 message += '\n'
        #             encoded_message = message.encode('utf-8')
        #             ser.write(encoded_message)
        #             print(f"Unrecognized key: {key}")
        #     time.sleep(0.05)  # Small delay to prevent high CPU usage


        while True:
            if msvcrt.kbhit():
                key = msvcrt.getch().decode('utf-8')
                if key.lower() == 'q':
                    print("Exiting program...")
                    break

            # Calculate the time for the current sample
            t = time.time()

            # Calculate the sine wave value
            value = int(amplitude * math.sin(2 * math.pi * frequency * t) + offset)

            # Ensure the value is within the 0-255 range
            value = max(0, min(value, 255))

            # Write the value to the serial port as a single byte
            ser.write(bytes([value]))  # Convert to byte array with a single element

            # Calculate the time to sleep for the next sample
            time_to_sleep = 1/sampling_frequency - (time.time() - t)

            # Wait for the calculated time
            if time_to_sleep > 0:
                time.sleep(time_to_sleep)











    except serial.SerialException as e:
        print(f"Serial exception: {e}")
    except KeyboardInterrupt:
        print("\nTermination requested by user.")
    except Exception as ex:
        print(f"An error occurred: {ex}")
    finally:
        if 'ser' in locals() and ser.is_open:
            ser.close()
            print(f"Closed serial port: {SERIAL_PORT}")

if __name__ == "__main__":
    main()