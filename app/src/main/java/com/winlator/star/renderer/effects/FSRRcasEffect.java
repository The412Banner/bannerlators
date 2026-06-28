package com.winlator.star.renderer.effects;

import com.winlator.star.renderer.material.ScreenMaterial;
import com.winlator.star.renderer.material.ShaderMaterial;

// =============================================================================
//  AMD FidelityFX Super Resolution 1.0 - RCAS (Robust Contrast-Adaptive Sharpen).
//
//  Algorithm (FsrRcasF) ported from AMD's reference header
//    GPUOpen-Effects/FidelityFX-FSR  ffx-fsr/ffx_fsr1.h, via the Winlator native
//    Vulkan compositor port (app/src/main/cpp/winlator/fsr_rcas.frag).
//
//  Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
//  SPDX-License-Identifier: MIT
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files (the "Software"), to deal
//  in the Software without restriction ... THE SOFTWARE IS PROVIDED "AS IS".
//  (Full MIT text retained from the source header.)
//
//  GL EffectComposer port (pass 2 of 2; EASU is FSREasuEffect):
//   - GLSL ES 3.00; input = the EASU output (already at output resolution), read 1:1
//     via texelFetch with edge clamping (no textureGather, so ES-3.0 safe as written).
//   - the bit-packed sharpness push-constant is replaced by a plain `sharpness` uniform
//     (0..1) used directly as the RCAS lobe scale: 0 = neutral (passthrough), 1 = full RCAS.
// =============================================================================

public class FSRRcasEffect extends Effect {

    @Override
    protected ShaderMaterial createMaterial() {
        return new RcasMaterial();
    }

    private static class RcasMaterial extends ScreenMaterial {
        RcasMaterial() {
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
                "uniform sampler2D screenTexture; // EASU output, output res",
                "uniform vec2 resolution;",
                "uniform vec2 srcResolution;",
                "uniform float sharpness;         // 0..1 from the slider",
                "out vec4 outColor;",

                // ---- minimal ffx_a.h fp32 portability layer (uint helpers removed) ----
                "#define AF1 float",
                "#define AF2 vec2",
                "#define AF3 vec3",
                "#define ASU2 ivec2",
                "#define AF1_(a) float(a)",
                "float ARcpF1(float x)      { return 1.0 / x; }",
                "float APrxMedRcpF1(float x){ return 1.0 / x; }",
                "float ASatF1(float x)      { return clamp(x, 0.0, 1.0); }",
                "float AMin3F1(float a, float b, float c) { return min(a, min(b, c)); }",
                "float AMax3F1(float a, float b, float c) { return max(a, max(b, c)); }",
                "#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))",

                "vec4 FsrRcasLoadF(ASU2 p) {",
                "    ivec2 sz = textureSize(screenTexture, 0);",
                "    return texelFetch(screenTexture, clamp(p, ivec2(0), sz - ivec2(1)), 0);",
                "}",

                "void FsrRcasF(out AF1 pixR, out AF1 pixG, out AF1 pixB, AF2 ip, float rcasScale){",
                "  ASU2 sp=ASU2(ip);",
                "  AF3 b=FsrRcasLoadF(sp+ASU2( 0,-1)).rgb;",
                "  AF3 d=FsrRcasLoadF(sp+ASU2(-1, 0)).rgb;",
                "  AF3 e=FsrRcasLoadF(sp).rgb;",
                "  AF3 f=FsrRcasLoadF(sp+ASU2( 1, 0)).rgb;",
                "  AF3 h=FsrRcasLoadF(sp+ASU2( 0, 1)).rgb;",
                "  AF1 bR=b.r; AF1 bG=b.g; AF1 bB=b.b;",
                "  AF1 dR=d.r; AF1 dG=d.g; AF1 dB=d.b;",
                "  AF1 eR=e.r; AF1 eG=e.g; AF1 eB=e.b;",
                "  AF1 fR=f.r; AF1 fG=f.g; AF1 fB=f.b;",
                "  AF1 hR=h.r; AF1 hG=h.g; AF1 hB=h.b;",
                "  AF1 bL=bB*AF1_(0.5)+(bR*AF1_(0.5)+bG);",
                "  AF1 dL=dB*AF1_(0.5)+(dR*AF1_(0.5)+dG);",
                "  AF1 eL=eB*AF1_(0.5)+(eR*AF1_(0.5)+eG);",
                "  AF1 fL=fB*AF1_(0.5)+(fR*AF1_(0.5)+fG);",
                "  AF1 hL=hB*AF1_(0.5)+(hR*AF1_(0.5)+hG);",
                "  AF1 nz=AF1_(0.25)*bL+AF1_(0.25)*dL+AF1_(0.25)*fL+AF1_(0.25)*hL-eL;",
                "  nz=ASatF1(abs(nz)*APrxMedRcpF1(AMax3F1(AMax3F1(bL,dL,eL),fL,hL)-AMin3F1(AMin3F1(bL,dL,eL),fL,hL)));",
                "  nz=AF1_(-0.5)*nz+AF1_(1.0);",
                "  AF1 mn4R=min(AMin3F1(bR,dR,fR),hR);",
                "  AF1 mn4G=min(AMin3F1(bG,dG,fG),hG);",
                "  AF1 mn4B=min(AMin3F1(bB,dB,fB),hB);",
                "  AF1 mx4R=max(AMax3F1(bR,dR,fR),hR);",
                "  AF1 mx4G=max(AMax3F1(bG,dG,fG),hG);",
                "  AF1 mx4B=max(AMax3F1(bB,dB,fB),hB);",
                "  AF2 peakC=AF2(1.0,-1.0*4.0);",
                "  AF1 hitMinR=min(mn4R,eR)*ARcpF1(AF1_(4.0)*mx4R);",
                "  AF1 hitMinG=min(mn4G,eG)*ARcpF1(AF1_(4.0)*mx4G);",
                "  AF1 hitMinB=min(mn4B,eB)*ARcpF1(AF1_(4.0)*mx4B);",
                "  AF1 hitMaxR=(peakC.x-max(mx4R,eR))*ARcpF1(AF1_(4.0)*mn4R+peakC.y);",
                "  AF1 hitMaxG=(peakC.x-max(mx4G,eG))*ARcpF1(AF1_(4.0)*mn4G+peakC.y);",
                "  AF1 hitMaxB=(peakC.x-max(mx4B,eB))*ARcpF1(AF1_(4.0)*mn4B+peakC.y);",
                "  AF1 lobeR=max(-hitMinR,hitMaxR);",
                "  AF1 lobeG=max(-hitMinG,hitMaxG);",
                "  AF1 lobeB=max(-hitMinB,hitMaxB);",
                "  AF1 lobe=max(AF1_(-FSR_RCAS_LIMIT),min(AMax3F1(lobeR,lobeG,lobeB),AF1_(0.0)))*rcasScale;",
                "  AF1 rcpL=APrxMedRcpF1(AF1_(4.0)*lobe+AF1_(1.0));",
                "  pixR=(lobe*bR+lobe*dR+lobe*hR+lobe*fR+eR)*rcpL;",
                "  pixG=(lobe*bG+lobe*dG+lobe*hG+lobe*fG+eG)*rcpL;",
                "  pixB=(lobe*bB+lobe*dB+lobe*hB+lobe*fB+eB)*rcpL;",
                "}",

                "void main() {",
                // Slider drives the RCAS lobe scale linearly: 0 = no sharpening (true
                // passthrough; with rcasScale 0 the lobe is 0 so the output == the EASU-upscaled
                // center pixel), 1 = full RCAS (== 0 stops, the algorithmic ceiling). No over-drive
                // past spec, so no ringing.
                "    float rcasScale = clamp(sharpness, 0.0, 1.0);",
                "    vec2 ip = floor(gl_FragCoord.xy);",
                "    vec3 c;",
                "    FsrRcasF(c.r, c.g, c.b, ip, rcasScale);",
                "    outColor = vec4(c, 1.0);",
                "}"
            });
        }
    }
}
