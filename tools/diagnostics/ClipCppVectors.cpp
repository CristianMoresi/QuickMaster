// Compile against unmodified DSPark 474b7d1 headers, never Java equations.
#include "Effects/Clipper.h"
#include <iostream>
#include <iomanip>
#include <vector>

int main() {
    using Clip = dspark::Clipper<double>;
    std::cout << std::setprecision(17);
    for(int mode=0;mode<4;mode++)for(double db : {-60.,-18.,-6.,0.}) {
        Clip clip;clip.setMode(static_cast<Clip::Mode>(mode));clip.setCeiling(db);
        clip.setSlewLimit(0);clip.setOversampling(1);clip.prepare({48000,401,1});
        std::vector<double> samples(401),source(401);
        double ceiling=std::pow(10.,db/20.);
        for(int i=0;i<401;i++)source[i]=samples[i]=ceiling*(i-200)/50.;
        double* channels[]={samples.data()};clip.processBlock(dspark::AudioBufferView<double>(channels,1,401));
        for(int i=0;i<401;i++)std::cout<<mode<<' '<<ceiling<<' '<<source[i]<<' '<<samples[i]<<'\n';
    }
}
