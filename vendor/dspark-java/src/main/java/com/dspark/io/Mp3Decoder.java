// DSPark - Copyright (c) 2026 Cristian Moresi - MIT License
package com.dspark.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import static com.dspark.io.Mp3Tables.*;

/** Offline MPEG-1 Layer III decoder, ported from DSPark C++ a8556aa.
 * Double-precision synthesis, interleaved float output; no PCM16 bridge or clamp.
 * Each invocation owns its reservoir and filter history. See mp3-decoder-port.md. */
public final class Mp3Decoder {
    private static final int[][] LONG_BANDS = {
        {0,4,8,12,16,20,24,30,36,44,52,62,74,90,110,134,162,196,238,288,342,418,576},
        {0,4,8,12,16,20,24,30,36,42,50,60,72,88,106,128,156,190,230,276,330,384,576},
        {0,4,8,12,16,20,24,30,36,44,54,66,82,102,126,156,194,240,296,364,448,550,576}};
    private static final int[][] SHORT_BANDS = {
        {0,4,8,12,16,22,30,40,52,66,84,106,136,192},
        {0,4,8,12,16,22,28,38,50,64,80,100,126,192},
        {0,4,8,12,16,22,30,42,58,78,104,138,180,192}};
    private static final double[] POW43 = new double[8208], CS = new double[8], CA = new double[8];
    private static final double[][] COS36 = new double[36][18], COS12 = new double[12][6], SYNTH = new double[64][32];
    private static final Huffman[] HUFF = new Huffman[32];
    private static final Huffman[] COUNT = {new Huffman(kCount1A, true), new Huffman(kCount1B, true)};
    static {
        for (int i=0;i<POW43.length;i++) POW43[i]=Math.pow(i,4.0/3.0);
        double[] ci={-.6,-.535,-.33,-.185,-.095,-.041,-.0142,-.0037};
        for(int i=0;i<8;i++) {CS[i]=1/Math.sqrt(1+ci[i]*ci[i]);CA[i]=ci[i]*CS[i];}
        for(int k=0;k<36;k++) for(int n=0;n<18;n++) COS36[k][n]=Math.cos(Math.PI/72*(2*k+19)*(2*n+1));
        for(int k=0;k<12;k++) for(int n=0;n<6;n++) COS12[k][n]=Math.cos(Math.PI/24*(2*k+7)*(2*n+1));
        for(int i=0;i<64;i++) for(int k=0;k<32;k++) SYNTH[i][k]=Math.cos(Math.PI/64*(16+i)*(2*k+1));
        int[][][] codes={null,kHuff01,kHuff02,kHuff03,null,kHuff05,kHuff06,kHuff07,
                kHuff08,kHuff09,kHuff10,kHuff11,kHuff12,kHuff13,null,kHuff15};
        for(int i=1;i<16;i++) if(codes[i]!=null) HUFF[i]=new Huffman(codes[i],false);
        Huffman h16=new Huffman(kHuff16,false),h24=new Huffman(kHuff24,false);
        for(int i=16;i<32;i++) HUFF[i]=i<24?h16:h24;
    }

    private final Channel[] states={new Channel(),new Channel()};
    private final Granule[][] granules={{new Granule(),new Granule()},{new Granule(),new Granule()}};
    private final int[] scfsi=new int[2], quantized=new int[576];
    private final int[][] scalefactors=new int[2][39];
    private final double[][] spectral=new double[2][576];
    private final boolean[] intensityUsed = new boolean[576];
    private final double[] tmp=new double[576], hybrid=new double[576], in=new double[18], out=new double[36], overlap=new double[36];
    private final double[] s=new double[32], v=new double[64], u=new double[512];
    private final byte[] reservoir=new byte[511], mainData=new byte[2048];
    private int reservoirSize;
    private int[] longBands, shortBands;

    private Mp3Decoder() {}
    public static Mp3Stream.Audio decode(Path path) throws IOException { return decode(Mp3Stream.read(path)); }
    public static Mp3Stream.Audio decode(Mp3Stream stream) throws IOException {
        if(stream.version()!=1) throw new IOException("DSPark's MPEG-1 decoder does not support MPEG-2/2.5");
        return new Mp3Decoder().decodeAll(stream);
    }

    private Mp3Stream.Audio decodeAll(Mp3Stream stream) throws IOException {
        Mp3Stream.checkCancelled();
        int table=stream.sampleRate()==44100?0:stream.sampleRate()==48000?1:2;
        longBands=LONG_BANDS[table];shortBands=SHORT_BANDS[table];
        float[] pcm=new float[stream.rawFrames()*stream.channels()];
        int framePosition=0;
        for(int offset:stream.offsets) {
            Mp3Stream.checkCancelled();
            Mp3Stream.Header header=stream.header(offset);
            int begin=readSide(new Bits(stream.data,offset+header.headerSize(),header.sideSize()),header.channels());
            if(begin>reservoirSize) throw Mp3Stream.bad("Missing MP3 bit reservoir at byte "+offset);
            int payload=offset+header.headerSize()+header.sideSize();
            int length=header.size()-header.headerSize()-header.sideSize();
            System.arraycopy(reservoir,reservoirSize-begin,mainData,0,begin);
            System.arraycopy(stream.data,payload,mainData,begin,length);
            int keep=Math.min(511,begin+length);
            // The reservoir is prior frame payload, including bytes not used by this frame.
            if(length>=511) System.arraycopy(stream.data,payload+length-511,reservoir,0,511);
            else {
                int old=Math.min(reservoirSize,511-length);
                System.arraycopy(reservoir,reservoirSize-old,reservoir,0,old);
                System.arraycopy(stream.data,payload,reservoir,old,length);
                keep=old+length;
            }
            reservoirSize=keep;
            Bits bits=new Bits(mainData,0,begin+length);
            for(int gr=0;gr<2;gr++) {
                for(int ch=0;ch<header.channels();ch++) {
                    Granule g=granules[gr][ch];
                    int end=bits.pos+g.partLength;
                    if(end>bits.size) throw Mp3Stream.bad("MP3 granule exceeds available main data");
                    bits.limit=end;
                    decodeScalefactors(bits,g,gr,scfsi[ch],scalefactors[ch]);
                    huffmanDecode(bits,g,end);
                    requantize(g,scalefactors[ch],spectral[ch]);
                    bits.pos=end;bits.limit=bits.size;
                }
                if(header.channels()==2) stereoProcess(header,granules[gr]);
                for(int ch=0;ch<header.channels();ch++) {
                    Granule g=granules[gr][ch];
                    reorder(spectral[ch],g);
                    aliasReduction(spectral[ch],g);
                    imdct(spectral[ch],g,states[ch]);
                    for(int sb=1;sb<32;sb+=2) for(int i=1;i<18;i+=2) hybrid[sb*18+i]=-hybrid[sb*18+i];
                    synthesize(states[ch],pcm,framePosition,ch,header.channels());
                }
                framePosition+=576;
            }
        }
        return stream.finish(pcm);
    }

    private int readSide(Bits b,int channels) throws IOException {
        int begin=b.read(9);b.read(channels==1?5:3);
        for(int ch=0;ch<channels;ch++) scfsi[ch]=b.read(4);
        for(int gr=0;gr<2;gr++) for(int ch=0;ch<channels;ch++) {
            Granule g=granules[gr][ch];
            g.partLength=b.read(12);g.bigValues=b.read(9);g.gain=b.read(8);g.compress=b.read(4);
            g.switched=b.read(1)!=0;
            if(g.bigValues>288) throw Mp3Stream.bad("Invalid MP3 big_values");
            if(g.switched) {
                g.type=b.read(2);g.mixed=b.read(1)!=0;
                if(g.type==0) throw Mp3Stream.bad("Invalid MP3 switched block type");
                g.tables[0]=b.read(5);g.tables[1]=b.read(5);g.tables[2]=0;
                for(int i=0;i<3;i++) g.subgain[i]=b.read(3);
                g.region0=g.type==2&&!g.mixed?8:7;g.region1=20-g.region0;
            } else {
                g.type=0;g.mixed=false;
                for(int i=0;i<3;i++) {g.tables[i]=b.read(5);g.subgain[i]=0;}
                g.region0=b.read(4);g.region1=b.read(3);
            }
            for(int t:g.tables) if(t==4||t==14) throw Mp3Stream.bad("Reserved MP3 Huffman table");
            g.pre=b.read(1);g.scale=b.read(1);g.count=b.read(1);
        }
        return begin;
    }

    private void decodeScalefactors(Bits b,Granule g,int gr,int reuse,int[] sf) throws IOException {
        boolean sh=g.switched&&g.type==2;
        if(gr==0||sh) Arrays.fill(sf,0);
        int a=kSlen1[g.compress],z=kSlen2[g.compress];
        if(sh) {
            if(g.mixed) for(int band=0;band<8;band++) sf[band]=b.read(a);
            for(int band=g.mixed?3:0;band<12;band++) for(int win=0;win<3;win++)
                sf[band*3+win-(g.mixed?1:0)]=b.read(band<6?a:z);
        } else {
            int[] bounds={0,6,11,16,21};
            for(int group=0;group<4;group++) {
                if(gr==1&&(reuse&(8>>group))!=0) continue;
                for(int band=bounds[group];band<bounds[group+1];band++) sf[band]=b.read(group<2?a:z);
            }
            Arrays.fill(sf,21,39,0);
        }
    }

    private void huffmanDecode(Bits b,Granule g,int end) throws IOException {
        Arrays.fill(quantized,0);
        int r1=g.switched&&g.type==2?36:longBands[Math.min(g.region0+1,22)];
        int r2=g.switched&&g.type==2?576:longBands[Math.min(g.region0+g.region1+2,22)];
        int i=0;
        for(;i<g.bigValues*2;i+=2) {
            int t=g.tables[i<r1?0:i<r2?1:2];
            if(t==0) continue;
            int pair=HUFF[t].read(b), x=pair>>>4, y=pair&15, lin=kHuffLinbits[t];
            if(lin>0&&x==15) x+=b.read(lin);
            if(x!=0&&b.read(1)!=0) x=-x;
            if(lin>0&&y==15) y+=b.read(lin);
            if(y!=0&&b.read(1)!=0) y=-y;
            quantized[i]=x;quantized[i+1]=y;
        }
        while(i+3<576&&b.pos<end) {
            int p=b.pos;
            try {
                int quad=COUNT[g.count].read(b);
                int a=(quad>>>3)&1,c=(quad>>>2)&1,d=(quad>>>1)&1,e=quad&1;
                if(a!=0&&b.read(1)!=0)a=-1;if(c!=0&&b.read(1)!=0)c=-1;
                if(d!=0&&b.read(1)!=0)d=-1;if(e!=0&&b.read(1)!=0)e=-1;
                quantized[i]=a;quantized[i+1]=c;quantized[i+2]=d;quantized[i+3]=e;i+=4;
            } catch(EndOfBits ex) { b.pos=p;break; } // incomplete stuffing quad is not audio
        }
        b.pos=end;
    }

    private void requantize(Granule g,int[] sf,double[] xr) {
        double gain=Math.pow(2,(g.gain-210.0)/4),scale=g.scale!=0?1:.5;
        boolean sh=g.switched&&g.type==2;
        int longCount=sh?(g.mixed?8:0):22;
        for(int band=0;band<longCount;band++) {
            double mult=gain*Math.pow(2,-scale*(sf[band]+g.pre*kPretab[band]));
            for(int i=longBands[band];i<longBands[band+1];i++) xr[i]=mult*pow43(quantized[i]);
        }
        if(sh) for(int band=g.mixed?3:0;band<13;band++) {
            int width=shortBands[band+1]-shortBands[band],base=3*shortBands[band];
            for(int win=0;win<3;win++) {
                double mult=gain*Math.pow(2,-2.0*g.subgain[win]-scale*sf[band*3+win-(g.mixed?1:0)]);
                for(int k=0;k<width;k++) {int i=base+win*width+k;xr[i]=mult*pow43(quantized[i]);}
            }
        }
    }
    private static double pow43(int n) {return n<0?-POW43[-n]:POW43[n];}

    private void stereoProcess(Mp3Stream.Header h,Granule[] g) {
        boolean ms=h.mode()==1&&(h.modeExt()&2)!=0, intensity=h.mode()==1&&(h.modeExt()&1)!=0;
        if(!ms&&!intensity)return;
        boolean[] is=intensityUsed;
        Arrays.fill(is, false);
        if(intensity) {
            boolean shortBlock=g[1].switched&&g[1].type==2;
            if(shortBlock) {
                int first=g[1].mixed?3:0;
                boolean anyShort=false;
                for(int win=0;win<3;win++) {
                    int highest=first-1;
                    for(int band=first;band<13;band++) {
                        int width=shortBands[band+1]-shortBands[band],base=3*shortBands[band]+win*width;
                        for(int k=0;k<width;k++) if(spectral[1][base+k]!=0) {highest=band;anyShort=true;break;}
                    }
                    for(int band=highest+1;band<13;band++) {
                        int width=shortBands[band+1]-shortBands[band],base=3*shortBands[band]+win*width;
                        int sf=scalefactors[1][Math.min(band,11)*3+win-(g[1].mixed?1:0)];
                        intensity(base,base+width,sf,is);
                    }
                }
                if(g[1].mixed&&!anyShort) intensityLong(8,is);
            } else intensityLong(22,is);
        }
        if(ms) for(int i=0;i<576;i++) if(!is[i]) {
            double m=spectral[0][i],s=spectral[1][i];
            spectral[0][i]=(m+s)*.7071067811865476;spectral[1][i]=(m-s)*.7071067811865476;
        }
    }
    private void intensityLong(int bands,boolean[] used) {
        int highest=-1;
        for(int band=0;band<bands;band++) for(int i=longBands[band];i<longBands[band+1];i++)
            if(spectral[1][i]!=0) {highest=band;break;}
        for(int band=highest+1;band<bands;band++)
            intensity(longBands[band],longBands[band+1],scalefactors[1][Math.min(band,20)],used);
    }
    private void intensity(int start,int end,int sf,boolean[] used) {
        if(sf>=7)return;
        double ratio=sf==6?Double.POSITIVE_INFINITY:Math.tan(sf*Math.PI/12);
        double left=sf==6?1:ratio/(1+ratio),right=sf==6?0:1/(1+ratio);
        // MPEG-1 intensity ratios operate on the coded left channel directly.
        for(int i=start;i<end;i++) {double x=spectral[0][i];spectral[0][i]=x*left;spectral[1][i]=x*right;used[i]=true;}
    }

    private void reorder(double[] xr,Granule g) {
        if(!g.switched||g.type!=2)return;
        System.arraycopy(xr,0,tmp,0,576);
        for(int band=g.mixed?3:0;band<13;band++) {
            int width=shortBands[band+1]-shortBands[band],base=3*shortBands[band];
            for(int win=0;win<3;win++)for(int k=0;k<width;k++) tmp[base+k*3+win]=xr[base+win*width+k];
        }
        System.arraycopy(tmp,0,xr,0,576);
    }
    private static void aliasReduction(double[] xr,Granule g) {
        if(g.switched&&g.type==2&&!g.mixed)return;
        int limit=g.switched&&g.type==2?1:31;
        for(int sb=0;sb<limit;sb++)for(int i=0;i<8;i++) {
            int a=(sb+1)*18-1-i,b=(sb+1)*18+i;double x=xr[a],y=xr[b];
            xr[a]=x*CS[i]-y*CA[i];xr[b]=y*CS[i]+x*CA[i];
        }
    }
    private void imdct(double[] xr,Granule g,Channel state) {
        for(int sb=0;sb<32;sb++) {
            boolean sh=g.switched&&g.type==2&&(!g.mixed||sb>=2);
            if(sh) {
                Arrays.fill(overlap,0);
                for(int win=0;win<3;win++) {
                    for(int n=0;n<6;n++)in[n]=xr[sb*18+win+3*n];
                    transform(in,out,COS12,6);
                    for(int n=0;n<12;n++)overlap[win*6+n+6]+=out[n]*kShortWindow[n];
                }
            } else {
                System.arraycopy(xr,sb*18,in,0,18);transform(in,overlap,COS36,18);
                double[] window=g.type==1?kStartWindow:g.type==3?kStopWindow:kNormalWindow;
                for(int n=0;n<36;n++)overlap[n]*=window[n];
            }
            for(int n=0;n<18;n++) {int i=sb*18+n;hybrid[i]=overlap[n]+state.prev[i];state.prev[i]=overlap[n+18];}
        }
    }
    private static void transform(double[] in,double[] out,double[][] matrix,int count) {
        for(int k=0;k<matrix.length;k++) {double sum=0;for(int n=0;n<count;n++)sum+=in[n]*matrix[k][n];out[k]=sum;}
    }
    private void synthesize(Channel state,float[] pcm,int frame,int ch,int channels) {
        for(int ss=0;ss<18;ss++) {
            for(int sb=0;sb<32;sb++)s[sb]=hybrid[sb*18+ss];
            transform(s,v,SYNTH,32);
            state.offset=(state.offset-64)&1023;
            for(int i=0;i<64;i++)state.synth[(state.offset+i)&1023]=v[i];
            for(int i=0;i<8;i++)for(int j=0;j<32;j++) {
                u[i*64+j]=state.synth[(state.offset+i*128+j)&1023];
                u[i*64+32+j]=state.synth[(state.offset+i*128+96+j)&1023];
            }
            for(int j=0;j<32;j++) {
                double sum=0;for(int i=0;i<16;i++)sum+=u[i*32+j]*kSynthWindow[i*32+j];
                pcm[(frame+ss*32+j)*channels+ch]=(float)sum;
            }
        }
    }

    private static final class Granule {
        int partLength,bigValues,gain,compress,type,region0,region1,pre,scale,count;
        boolean switched,mixed;
        final int[] tables=new int[3],subgain=new int[3];
    }
    private static final class Channel {final double[] prev=new double[576],synth=new double[1024];int offset;}
    private static final class EndOfBits extends IOException {EndOfBits(){super("Truncated MP3 granule bits");}}
    private static final class Bits {
        final byte[] bytes;final int offset,size;int pos,limit;
        Bits(byte[] bytes,int offset,int length){this.bytes=bytes;this.offset=offset;size=length*8;limit=size;}
        int read(int n)throws IOException {
            if(n<0||n>24||n>limit-pos)throw new EndOfBits();
            int value=0;
            for(int i=0;i<n;i++,pos++)value=(value<<1)|((bytes[offset+(pos>>>3)]>>>(7-(pos&7)))&1);
            return value;
        }
    }
    /** Exact prefix trees from the native canonical tables; no per-symbol table scan. */
    private static final class Huffman {
        final int[] left,right,symbol;
        Huffman(int[][] codes,boolean quad) {
            int capacity=codes.length*2+32,nodes=1;
            left=new int[capacity];right=new int[capacity];symbol=new int[capacity];Arrays.fill(symbol,-1);
            for(int[] row:codes) {
                int node=0;
                for(int bit=row[0]-1;bit>=0;bit--) {
                    if(symbol[node]>=0)throw new ExceptionInInitializerError("Non-prefix-free MP3 table");
                    int[] branch=((row[1]>>>bit)&1)==0?left:right;
                    if(branch[node]==0)branch[node]=nodes++;
                    node=branch[node];
                }
                if(symbol[node]>=0||left[node]!=0||right[node]!=0)throw new ExceptionInInitializerError("Duplicate MP3 code");
                symbol[node]=quad?(row[2]<<3)|(row[3]<<2)|(row[4]<<1)|row[5]:(row[2]<<4)|row[3];
            }
        }
        int read(Bits b)throws IOException {
            int n=0;
            while(symbol[n]<0) {n=b.read(1)==0?left[n]:right[n];if(n==0)throw Mp3Stream.bad("Invalid Huffman code");}
            return symbol[n];
        }
    }
}
