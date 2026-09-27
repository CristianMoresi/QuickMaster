// Reference vectors from the pinned upstream C++ headers. No Java implementation involved.
#include "Core/FFT.h"
#include "Core/Biquad.h"
#include <iostream>
#include <iomanip>
#include <vector>
#include <cmath>

int main() {
    std::cout << std::setprecision(17);
    for (int n : {2, 4, 8, 16, 64, 256, 1024, 8192}) {
        dspark::FFTComplex<double> fft(n);
        std::vector<double> x(2*n);
        for (int i=0;i<2*n;i++) x[i]=static_cast<float>(std::sin(i*0.371)+0.3*std::cos(i*0.173));
        fft.forward(x.data());
        for (int k=0;k<n;k++) std::cout << "FFT " << n << ' ' << k << ' ' << x[2*k] << ' ' << x[2*k+1] << '\n';
    }
    for(double rate : {44100.,48000.,96000.})
        for(double freq : {200.,1000.,10000.,20000.})
            for(double q : {.1,.5,1.,4.,12.})
                for(double gain : {-24.,-6.,6.,24.}) {
                    auto c=dspark::BiquadCoeffs::makePeakMatched(rate,freq,q,gain);
                    std::cout << "BELL " << rate << ' ' << freq << ' ' << q << ' ' << gain << ' '
                        << c.b0 << ' ' << c.b1 << ' ' << c.b2 << ' ' << c.a1 << ' ' << c.a2 << '\n';
                }
}
