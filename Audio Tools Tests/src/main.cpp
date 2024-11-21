#include <Arduino.h>

// #include "BluetoothSerial.h"
#include "AudioTools.h"
// #include "AudioTools/AudioLibs/R2ROutput.h"
// #include "AudioTools/AudioCodecs/CodecMP3Helix.h"

//TODO generate sine wave and write to serial / DONE
//TODO create python script to write to USB serial / DONE
//TODO read from USB serial and write to serial / DONE
//TODO output sine to headphone //DONE
//TODO read from USB serial and output to headphone
//TODO python script to send BT audio
//TODO flip bool if USB serial is provided
//TODO change input source on USB serial and disable BT



//-------------------------------------
//  Process 8 bit audio from serial, output as CSV back to serial
//-------------------------------------
// AudioInfo info(44100, 2, 16); // 44.1 kHz, 2 channels, 16 bit
// CsvOutput<int16_t> out(Serial); // output CSV to serial
// auto &serial = Serial;
// DecoderL8 dec; // 8 bit decoder
// EncodedAudioStream dec_stream(&out, &dec); // decodes 8 bit audio
// StreamCopy copierIn(dec_stream, serial, 256);     // copies sound from Serial

// void setup() {
//   // Open Serial 
//   Serial.begin(921600);
//   AudioToolsLogger.begin(Serial, AudioToolsLogLevel::Info);

//   dec_stream.begin(info);

//   // Define CSV Output
//   auto config = out.defaultConfig();
//   config.copyFrom(info); 
//   out.begin(config);
// }
// void loop() {
//   // copy from serial
//   copierIn.copy();
// }


//-------------------------------------
//  Generate sine wave and output to DAC
//-------------------------------------
// AudioInfo info(44100, 1, 16);
// SineWaveGenerator<int16_t> sineWave(32000);                // subclass of SoundGenerator with max amplitude of 32000
// GeneratedSoundStream<int16_t> sound(sineWave);             // Stream generated from sine wave

// AnalogAudioStream out; 
// StreamCopy copier(out, sound);                             // copies sound into i2s

// // Arduino Setup
// void setup(void) {  
//   // Open Serial 
//   Serial.begin(921600);
//   AudioToolsLogger.begin(Serial, AudioToolsLogLevel::Warning);

//   // start I2S
//   Serial.println("starting I2S...");
//   auto config = out.defaultConfig(TX_MODE);
//   config.copyFrom(info); 
//   out.begin(config);

//   // Setup sine wave
//   // sineWave.begin(info);
//   sineWave.begin(info, N_B3);
//   Serial.println("started...");
// }

// // Arduino loop - copy sound to out 
// void loop() {
//   copier.copy();
// }



//-------------------------------------
//  Process 8 bit audio from serial, output as to DAC
//-------------------------------------
AudioInfo info(44100, 1, 16);
AnalogAudioStream out; // or AnalogAudioStream, AudioBoardStream etc
SineWaveGenerator<int16_t> sineWave(32000);
GeneratedSoundStream<int16_t> sound(sineWave);
auto &serialOut = Serial;
auto &serialIn = Serial;
EncoderL8 enc;
DecoderL8 dec;
EncodedAudioStream enc_stream(&serialOut, &enc);
EncodedAudioStream dec_stream(&out, &dec);
Throttle throttle(enc_stream);
StreamCopy copierOut(throttle, sound, 256);  // copies sound into Serial
StreamCopy copierIn(dec_stream, serialIn, 256);     // copies sound from Serial

void setup() {
  Serial.begin(460800, SERIAL_8N1);
  // AudioToolsLogger.begin(Serial, AudioToolsLogLevel::Warning);

  // Note the format for setting a serial port is as follows:
  // Serial.begin(baud-rate, protocol, RX pin, TX pin);
  Serial2.begin(460800, SERIAL_8N1);

  sineWave.begin(info, N_B4);
  throttle.begin(info);
  enc_stream.begin(info);
  dec_stream.begin(info);

  // start I2S
  auto config = out.defaultConfig(TX_MODE);
  config.copyFrom(info);
  out.begin(config);

  // better visibility in logging
  copierOut.setLogName("out");
  copierIn.setLogName("in");
}

void loop() {
  // copy to serial
  copierOut.copy();
  // copy from serial
  copierIn.copy();
}



//-------------------------------------
//  Verify Baudrate is stable
//-------------------------------------

// // this will just echo serial back out
// void setup() {
//   Serial.begin(300000);
// }

// void loop() {
//   if (Serial.available()) {
//     Serial.write(Serial.read());
//   }
// }
