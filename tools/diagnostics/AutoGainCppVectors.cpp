// Ground truth from unmodified DSPark C++ 474b7d1, double specialization.
#include "Effects/AutoGain.h"
#include <iostream>
#include <iomanip>
#include <vector>
int main() {
    std::cout<<std::setprecision(17);
    for(int rate:{44100,48000,96000})for(int w=0;w<2;w++)for(int scenario=0;scenario<2;scenario++) {
        dspark::AutoGain<double> ag;ag.prepare({double(rate),1024,2});ag.setWeighting(static_cast<dspark::AutoGain<double>::Weighting>(w));
        int frame=0;
        for(int b=0;b<60;b++) {
            int n=std::vector<int>{17,64,257,1024}[b%4];std::vector<double> l(n),r(n);double* channels[]={l.data(),r.data()};
            for(int i=0;i<n;i++) {double t=double(frame+i)/rate;
                l[i]=float(.13*std::sin(2*std::numbers::pi*1000*t)+.3*std::sin(2*std::numbers::pi*40*t));r[i]=float(-.7*l[i]);}
            auto view=dspark::AudioBufferView<double>(channels,2,n);ag.pushReference(view);
            for(int i=0;i<n;i++) {double t=double(frame+i)/rate;
                if(scenario==0){l[i]=float(l[i]*(b<20?4:b<40?.25:1));r[i]=float(r[i]*(b<20?4:b<40?.25:1));}
                else {l[i]=float(l[i]+.6*std::sin(2*std::numbers::pi*40*t));r[i]=float(r[i]-.42*std::sin(2*std::numbers::pi*40*t));}}
            ag.compensate(view);
            std::cout<<rate<<' '<<w<<' '<<scenario<<' '<<b<<' '<<n<<' '<<ag.getCompensationDb()<<' '<<float(l[0])<<' '<<float(r[n/2])<<' '<<float(l[n-1])<<'\n';frame+=n;
        }
    }
}
