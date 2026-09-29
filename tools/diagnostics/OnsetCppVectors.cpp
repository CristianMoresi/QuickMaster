// Native reference: unmodified DSPark C++ 474b7d1, float FFT/ODF.
#include "Analysis/OnsetDetector.h"
#include <iostream>
#include <iomanip>
#include <cstdint>
#include <vector>

int main() {
    std::cout << std::setprecision(17);
    for(int rate : {44100,48000,96000})for(int kind=0;kind<3;kind++) {
        std::vector<float> x(rate*3);uint32_t state=817;double phase=0;
        for(int i=0;i<(int)x.size();i++) {
            state^=state<<13;state^=state>>17;state^=state<<5;
            double white=(double)state/4294967295.0*2-1,t=(double)i/rate;
            if(kind==0) {
                int local=i-rate/5;if(local<0)continue;int k=local/(rate/2),j=local%(rate/2);
                if(j<rate*.008)x[i]=(float)((k<3?.025:.9)*white*std::exp(-j/(.003*rate)));
            } else if(kind==1)x[i]=(float)(.5*std::sin(2*dspark::pi<double>*50*t));
            else {phase+=2*dspark::pi<double>*440*(1+.03*std::sin(2*dspark::pi<double>*6*t))/rate;
                x[i]=(float)(.5*std::sin(phase)*(.8+.2*std::sin(2*dspark::pi<double>*5*t)));}
        }
        dspark::OnsetDetector<float> detector;detector.prepare({(double)rate,512,1});
        detector.setThreshold(.01f);int hop=detector.getHopSize();
        for(int i=0,row=0;i+hop<=(int)x.size();i+=hop,row++) {
            detector.pushSamples(std::span<const float>(x.data()+i,hop));
            auto frame=detector.getLastOdfFrame();
            std::cout<<"ODF "<<rate<<' '<<kind<<' '<<row<<' '<<frame.value<<'\n';
        }
        const float* channels[]={x.data()};
        auto onsets=detector.detectOffline(dspark::AudioBufferView<const float>(channels,1,(int)x.size()));
        std::cout<<"TIMES "<<rate<<' '<<kind<<' '<<onsets.size();
        for(auto at:onsets)std::cout<<' '<<at;
        std::cout<<'\n';
    }
}
