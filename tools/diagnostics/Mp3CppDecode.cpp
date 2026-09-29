// Decode-only reference harness for unmodified DSPark C++ IO/Mp3File.h.
// Build against the pinned upstream revision documented in mp3-decoder-port.md.
#include "IO/Mp3File.h"
#include <iostream>
#include <fstream>
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    dspark::Mp3File decoder;
    if (!decoder.openRead(argv[1])) return 3;
    const auto info=decoder.getInfo();
    if(info.numSamples>100000000) return 4;
    dspark::AudioBuffer<float> buffer;
    buffer.resize(info.numChannels,static_cast<int>(info.numSamples));
    if(!decoder.readSamples(buffer.toView())) return 5;
    std::ofstream output(argv[2],std::ios::binary);
    for(int64_t frame=0;frame<info.numSamples;frame++) for(int ch=0;ch<info.numChannels;ch++) {
        const float value=buffer.getChannel(ch)[frame];
        output.write(reinterpret_cast<const char*>(&value),sizeof(value));
    }
    if(!output) return 6;
    std::cout << "CPP_DECODE rate=" << info.sampleRate << " channels=" << info.numChannels
              << " frames=" << info.numSamples << '\n';
}
