package com.winlator.star.renderer.effects;

import com.winlator.star.renderer.material.ScreenMaterial;
import com.winlator.star.renderer.material.ShaderMaterial;

// =============================================================================
//  AMD FidelityFX Super Resolution 1.0 - EASU (Edge-Adaptive Spatial Upsampling).
//
//  Algorithm (FsrEasuF / FsrEasuTapF / FsrEasuSetF) ported from AMD's reference
//  header GPUOpen-Effects/FidelityFX-FSR  ffx-fsr/ffx_fsr1.h, via the Winlator
//  native Vulkan compositor port (app/src/main/cpp/winlator/fsr_easu.frag).
//
//  Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to deal
//  in the Software without restriction ... THE SOFTWARE IS PROVIDED "AS IS".
//  (Full MIT text retained from the source header.)
//
//  GL EffectComposer port (pass 1 of 2; RCAS is FSRRcasEffect):
//   - GLSL ES 3.00; the con0..con3 constants are computed in-shader as plain vec4
//     (FsrEasuCon with inputViewport == inputSize == srcResolution) instead of the
//     CPU-side bit-packed uvec4 the Vulkan push-constant path uses.
//   - textureGather() (ES 3.1+) is emulated with four texelFetch() reads, since the
//     GL context is only guaranteed ES 3.0.
//   - input = the EffectComposer low-res render target (srcResolution); output = the
//     surface-resolution target.
// =============================================================================

public class FSREasuEffect extends Effect {

    @Override
    protected ShaderMaterial createMaterial() {
        return new EasuMaterial();
    }

    private static class EasuMaterial extends ScreenMaterial {
        EasuMaterial() {
            super();
            setUniformNames("resolution", "screenTexture", "srcResolution", "sharpness");
        }

        @Override
        protected String getVertexShader() {
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "layout(location = 0) in vec2 position;",
                "void main() {",
                "    gl_Position = vec4(2.0 * position.x - 1.0, 2.0 * position.y - 1.0, 0.0, 1.0);",
                "}"
            });
        }

        @Override
        protected String getFragmentShader() {
            return String.join("\n", new CharSequence[]{
                "#version 300 es",
                "precision highp float;",
                "uniform sampler2D screenTexture;",
                "uniform vec2 resolution;     // EASU output size (px)",
                "uniform vec2 srcResolution;  // low-res input size (px)",
                "uniform float sharpness;     // unused by EASU (RCAS uses it)",
                "out vec4 outColor;",

                // ---- minimal ffx_a.h fp32 portability layer (uint helpers removed) ----
                "#define AF1 float",
                "#define AF2 vec2",
                "#define AF3 vec3",
                "#define AF4 vec4",
                "#define AP1 bool",
                "#define AF1_(a) float(a)",
                "#define AF2_(a) vec2(AF1_(a))",
                "#define AF3_(a) vec3(AF1_(a))",
                "#define AF4_(a) vec4(AF1_(a))",
                "float ARcpF1(float x)      { return 1.0 / x; }",
                "float APrxLoRcpF1(float x) { return 1.0 / x; }",
                "float APrxLoRsqF1(float x) { return inversesqrt(x); }",
                "float ASatF1(float x)      { return clamp(x, 0.0, 1.0); }",
                "vec3  AMin3F3(vec3 a, vec3 b, vec3 c) { return min(a, min(b, c)); }",
                "vec3  AMax3F3(vec3 a, vec3 b, vec3 c) { return max(a, max(b, c)); }",

                // textureGather emulation (ES 3.0): component r/g/b at corner `coord`,
                // returned in textureGather order (.x=(i0,j1) .y=(i1,j1) .z=(i1,j0) .w=(i0,j0)).
                "vec4 FsrEasuRF(vec2 coord) {",
                "    ivec2 sz = ivec2(srcResolution) - ivec2(1);",
                "    vec2 f = coord * srcResolution - 0.5;",
                "    ivec2 i0 = ivec2(floor(f)); ivec2 i1 = i0 + ivec2(1);",
                "    return vec4(texelFetch(screenTexture, clamp(ivec2(i0.x,i1.y),ivec2(0),sz),0).r,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i1.y),ivec2(0),sz),0).r,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i0.y),ivec2(0),sz),0).r,",
                "                texelFetch(screenTexture, clamp(ivec2(i0.x,i0.y),ivec2(0),sz),0).r);",
                "}",
                "vec4 FsrEasuGF(vec2 coord) {",
                "    ivec2 sz = ivec2(srcResolution) - ivec2(1);",
                "    vec2 f = coord * srcResolution - 0.5;",
                "    ivec2 i0 = ivec2(floor(f)); ivec2 i1 = i0 + ivec2(1);",
                "    return vec4(texelFetch(screenTexture, clamp(ivec2(i0.x,i1.y),ivec2(0),sz),0).g,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i1.y),ivec2(0),sz),0).g,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i0.y),ivec2(0),sz),0).g,",
                "                texelFetch(screenTexture, clamp(ivec2(i0.x,i0.y),ivec2(0),sz),0).g);",
                "}",
                "vec4 FsrEasuBF(vec2 coord) {",
                "    ivec2 sz = ivec2(srcResolution) - ivec2(1);",
                "    vec2 f = coord * srcResolution - 0.5;",
                "    ivec2 i0 = ivec2(floor(f)); ivec2 i1 = i0 + ivec2(1);",
                "    return vec4(texelFetch(screenTexture, clamp(ivec2(i0.x,i1.y),ivec2(0),sz),0).b,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i1.y),ivec2(0),sz),0).b,",
                "                texelFetch(screenTexture, clamp(ivec2(i1.x,i0.y),ivec2(0),sz),0).b,",
                "                texelFetch(screenTexture, clamp(ivec2(i0.x,i0.y),ivec2(0),sz),0).b);",
                "}",

                "void FsrEasuTapF(inout AF3 aC, inout AF1 aW, AF2 off, AF2 dir, AF2 len,",
                "                 AF1 lob, AF1 clp, AF3 c){",
                "  AF2 v;",
                "  v.x=(off.x*( dir.x))+(off.y*dir.y);",
                "  v.y=(off.x*(-dir.y))+(off.y*dir.x);",
                "  v*=len;",
                "  AF1 d2=v.x*v.x+v.y*v.y;",
                "  d2=min(d2,clp);",
                "  AF1 wB=AF1_(2.0/5.0)*d2+AF1_(-1.0);",
                "  AF1 wA=lob*d2+AF1_(-1.0);",
                "  wB*=wB;",
                "  wA*=wA;",
                "  wB=AF1_(25.0/16.0)*wB+AF1_(-(25.0/16.0-1.0));",
                "  AF1 w=wB*wA;",
                "  aC+=c*w;aW+=w;}",

                "void FsrEasuSetF(inout AF2 dir, inout AF1 len, AF2 pp,",
                "                 AP1 biS,AP1 biT,AP1 biU,AP1 biV,",
                "                 AF1 lA,AF1 lB,AF1 lC,AF1 lD,AF1 lE){",
                "  AF1 w = AF1_(0.0);",
                "  if(biS)w=(AF1_(1.0)-pp.x)*(AF1_(1.0)-pp.y);",
                "  if(biT)w=           pp.x *(AF1_(1.0)-pp.y);",
                "  if(biU)w=(AF1_(1.0)-pp.x)*           pp.y ;",
                "  if(biV)w=           pp.x *           pp.y ;",
                "  AF1 dc=lD-lC;",
                "  AF1 cb=lC-lB;",
                "  AF1 lenX=max(abs(dc),abs(cb));",
                "  lenX=APrxLoRcpF1(lenX);",
                "  AF1 dirX=lD-lB;",
                "  dir.x+=dirX*w;",
                "  lenX=ASatF1(abs(dirX)*lenX);",
                "  lenX*=lenX;",
                "  len+=lenX*w;",
                "  AF1 ec=lE-lC;",
                "  AF1 ca=lC-lA;",
                "  AF1 lenY=max(abs(ec),abs(ca));",
                "  lenY=APrxLoRcpF1(lenY);",
                "  AF1 dirY=lE-lA;",
                "  dir.y+=dirY*w;",
                "  lenY=ASatF1(abs(dirY)*lenY);",
                "  lenY*=lenY;",
                "  len+=lenY*w;}",

                "void FsrEasuF(out AF3 pix, AF2 ip, AF4 con0, AF4 con1, AF4 con2, AF4 con3){",
                "  AF2 pp=ip*con0.xy+con0.zw;",
                "  AF2 fp=floor(pp);",
                "  pp-=fp;",
                "  AF2 p0=fp*con1.xy+con1.zw;",
                "  AF2 p1=p0+con2.xy;",
                "  AF2 p2=p0+con2.zw;",
                "  AF2 p3=p0+con3.xy;",
                "  AF4 bczzR=FsrEasuRF(p0);",
                "  AF4 bczzG=FsrEasuGF(p0);",
                "  AF4 bczzB=FsrEasuBF(p0);",
                "  AF4 ijfeR=FsrEasuRF(p1);",
                "  AF4 ijfeG=FsrEasuGF(p1);",
                "  AF4 ijfeB=FsrEasuBF(p1);",
                "  AF4 klhgR=FsrEasuRF(p2);",
                "  AF4 klhgG=FsrEasuGF(p2);",
                "  AF4 klhgB=FsrEasuBF(p2);",
                "  AF4 zzonR=FsrEasuRF(p3);",
                "  AF4 zzonG=FsrEasuGF(p3);",
                "  AF4 zzonB=FsrEasuBF(p3);",
                "  AF4 bczzL=bczzB*AF4_(0.5)+(bczzR*AF4_(0.5)+bczzG);",
                "  AF4 ijfeL=ijfeB*AF4_(0.5)+(ijfeR*AF4_(0.5)+ijfeG);",
                "  AF4 klhgL=klhgB*AF4_(0.5)+(klhgR*AF4_(0.5)+klhgG);",
                "  AF4 zzonL=zzonB*AF4_(0.5)+(zzonR*AF4_(0.5)+zzonG);",
                "  AF1 bL=bczzL.x; AF1 cL=bczzL.y;",
                "  AF1 iL=ijfeL.x; AF1 jL=ijfeL.y; AF1 fL=ijfeL.z; AF1 eL=ijfeL.w;",
                "  AF1 kL=klhgL.x; AF1 lL=klhgL.y; AF1 hL=klhgL.z; AF1 gL=klhgL.w;",
                "  AF1 oL=zzonL.z; AF1 nL=zzonL.w;",
                "  AF2 dir=AF2_(0.0);",
                "  AF1 len=AF1_(0.0);",
                "  FsrEasuSetF(dir,len,pp,true, false,false,false,bL,eL,fL,gL,jL);",
                "  FsrEasuSetF(dir,len,pp,false,true ,false,false,cL,fL,gL,hL,kL);",
                "  FsrEasuSetF(dir,len,pp,false,false,true ,false,fL,iL,jL,kL,nL);",
                "  FsrEasuSetF(dir,len,pp,false,false,false,true ,gL,jL,kL,lL,oL);",
                "  AF2 dir2=dir*dir;",
                "  AF1 dirR=dir2.x+dir2.y;",
                "  AP1 zro=dirR<AF1_(1.0/32768.0);",
                "  dirR=APrxLoRsqF1(dirR);",
                "  dirR=zro?AF1_(1.0):dirR;",
                "  dir.x=zro?AF1_(1.0):dir.x;",
                "  dir*=AF2_(dirR);",
                "  len=len*AF1_(0.5);",
                "  len*=len;",
                "  AF1 stretch=(dir.x*dir.x+dir.y*dir.y)*APrxLoRcpF1(max(abs(dir.x),abs(dir.y)));",
                "  AF2 len2=AF2(AF1_(1.0)+(stretch-AF1_(1.0))*len,AF1_(1.0)+AF1_(-0.5)*len);",
                "  AF1 lob=AF1_(0.5)+AF1_((1.0/4.0-0.04)-0.5)*len;",
                "  AF1 clp=APrxLoRcpF1(lob);",
                "  AF3 min4=min(AMin3F3(AF3(ijfeR.z,ijfeG.z,ijfeB.z),AF3(klhgR.w,klhgG.w,klhgB.w),AF3(ijfeR.y,ijfeG.y,ijfeB.y)),",
                "               AF3(klhgR.x,klhgG.x,klhgB.x));",
                "  AF3 max4=max(AMax3F3(AF3(ijfeR.z,ijfeG.z,ijfeB.z),AF3(klhgR.w,klhgG.w,klhgB.w),AF3(ijfeR.y,ijfeG.y,ijfeB.y)),",
                "               AF3(klhgR.x,klhgG.x,klhgB.x));",
                "  AF3 aC=AF3_(0.0);",
                "  AF1 aW=AF1_(0.0);",
                "  FsrEasuTapF(aC,aW,AF2( 0.0,-1.0)-pp,dir,len2,lob,clp,AF3(bczzR.x,bczzG.x,bczzB.x));",
                "  FsrEasuTapF(aC,aW,AF2( 1.0,-1.0)-pp,dir,len2,lob,clp,AF3(bczzR.y,bczzG.y,bczzB.y));",
                "  FsrEasuTapF(aC,aW,AF2(-1.0, 1.0)-pp,dir,len2,lob,clp,AF3(ijfeR.x,ijfeG.x,ijfeB.x));",
                "  FsrEasuTapF(aC,aW,AF2( 0.0, 1.0)-pp,dir,len2,lob,clp,AF3(ijfeR.y,ijfeG.y,ijfeB.y));",
                "  FsrEasuTapF(aC,aW,AF2( 0.0, 0.0)-pp,dir,len2,lob,clp,AF3(ijfeR.z,ijfeG.z,ijfeB.z));",
                "  FsrEasuTapF(aC,aW,AF2(-1.0, 0.0)-pp,dir,len2,lob,clp,AF3(ijfeR.w,ijfeG.w,ijfeB.w));",
                "  FsrEasuTapF(aC,aW,AF2( 1.0, 1.0)-pp,dir,len2,lob,clp,AF3(klhgR.x,klhgG.x,klhgB.x));",
                "  FsrEasuTapF(aC,aW,AF2( 2.0, 1.0)-pp,dir,len2,lob,clp,AF3(klhgR.y,klhgG.y,klhgB.y));",
                "  FsrEasuTapF(aC,aW,AF2( 2.0, 0.0)-pp,dir,len2,lob,clp,AF3(klhgR.z,klhgG.z,klhgB.z));",
                "  FsrEasuTapF(aC,aW,AF2( 1.0, 0.0)-pp,dir,len2,lob,clp,AF3(klhgR.w,klhgG.w,klhgB.w));",
                "  FsrEasuTapF(aC,aW,AF2( 1.0, 2.0)-pp,dir,len2,lob,clp,AF3(zzonR.z,zzonG.z,zzonB.z));",
                "  FsrEasuTapF(aC,aW,AF2( 0.0, 2.0)-pp,dir,len2,lob,clp,AF3(zzonR.w,zzonG.w,zzonB.w));",
                "  pix=min(max4,max(min4,aC*AF3_(ARcpF1(aW))));}",

                "void main() {",
                "    vec2 src = srcResolution;",
                "    vec2 outp = resolution;",
                "    vec4 con0 = vec4(src.x/outp.x, src.y/outp.y,",
                "                     0.5*src.x/outp.x - 0.5, 0.5*src.y/outp.y - 0.5);",
                "    vec4 con1 = vec4(1.0/src.x, 1.0/src.y, 1.0/src.x, -1.0/src.y);",
                "    vec4 con2 = vec4(-1.0/src.x, 2.0/src.y, 1.0/src.x, 2.0/src.y);",
                "    vec4 con3 = vec4(0.0, 4.0/src.y, 0.0, 0.0);",
                "    vec2 ip = floor(gl_FragCoord.xy);",
                "    vec3 c;",
                "    FsrEasuF(c, ip, con0, con1, con2, con3);",
                "    outColor = vec4(c, 1.0);",
                "}"
            });
        }
    }
}
