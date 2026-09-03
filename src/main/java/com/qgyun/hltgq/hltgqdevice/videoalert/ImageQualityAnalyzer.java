package com.qgyun.hltgq.hltgqdevice.videoalert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 视频图像质量分析器（纯 Java，零新增依赖）。
 * <p>对 ffmpeg 抽出的 JPEG 帧做像素级统计，判定 9 类 B 层图像故障：
 * 过亮、过暗、偏色、黑白、模糊、对比度、噪声、冻结、遮挡。
 * <p>故障判定阈值均为配置项 {@code video-alert.threshold.*}（默认值基于子码流
 * D1/CIF 分辨率 + 抽帧降采样 320 宽标定的经验初值），现场联调时按实际设备
 * 图像质量校准（重点：夜间红外场景过暗误报、背光场景过亮误报），
 * 以降采样后的实际统计值为准标定，无需改代码。
 * 像素级辅助边界（暗像素亮度&lt;40、亮像素亮度&gt;215 等）为物理量，保持硬编码。
 */
@Slf4j
@Component
public class ImageQualityAnalyzer {

    /** 单帧亮度均值低于此值（0~255）判"过暗"候选，配置项 video-alert.threshold.too-dark-mean */
    @Value("${video-alert.threshold.too-dark-mean:25.0}")
    private double tooDarkMean;

    /** 单帧亮度均值高于此值判"过亮"候选，配置项 video-alert.threshold.too-bright-mean */
    @Value("${video-alert.threshold.too-bright-mean:205.0}")
    private double tooBrightMean;

    /** 暗像素（亮度<40）占比超过此值且亮度均值偏低 → 过暗，配置项 video-alert.threshold.dark-pixel-ratio */
    @Value("${video-alert.threshold.dark-pixel-ratio:0.70}")
    private double darkPixelRatio;

    /** 亮像素（亮度>215）占比超过此值且亮度均值偏高 → 过亮，配置项 video-alert.threshold.bright-pixel-ratio */
    @Value("${video-alert.threshold.bright-pixel-ratio:0.70}")
    private double brightPixelRatio;

    /** RGB 通道均值两两最大差超过此值 → 偏色，配置项 video-alert.threshold.color-cast-delta */
    @Value("${video-alert.threshold.color-cast-delta:40.0}")
    private double colorCastDelta;

    /** 饱和度均值（0~255）低于此值 → 黑白图像，配置项 video-alert.threshold.grayscale-saturation */
    @Value("${video-alert.threshold.grayscale-saturation:12.0}")
    private double grayscaleSaturation;

    /** 灰度标准差低于此值 → 对比度过低（画面灰蒙），配置项 video-alert.threshold.contrast-std-dev */
    @Value("${video-alert.threshold.contrast-std-dev:18.0}")
    private double contrastStdDev;

    /** Laplacian 方差低于此值 → 模糊（失焦/镜头脏污），配置项 video-alert.threshold.blur-laplacian-var */
    @Value("${video-alert.threshold.blur-laplacian-var:150.0}")
    private double blurLaplacianVar;

    /** 噪声残差（与3x3邻域均值的平均绝对差）超过此值 → 噪声干扰，配置项 video-alert.threshold.noise-residual */
    @Value("${video-alert.threshold.noise-residual:12.0}")
    private double noiseResidual;

    /** 帧间平均绝对差（MAD）低于此值 → 画面冻结，配置项 video-alert.threshold.frozen-mad */
    @Value("${video-alert.threshold.frozen-mad:0.6}")
    private double frozenMad;

    /** 遮挡：Laplacian 方差低于此值且灰度标准差低于 contrastStdDev（画面近似纯色），配置项 video-alert.threshold.occluded-laplacian-var */
    @Value("${video-alert.threshold.occluded-laplacian-var:60.0}")
    private double occludedLaplacianVar;

    /** 遮挡灰度标准差上限，配置项 video-alert.threshold.occluded-std-dev */
    @Value("${video-alert.threshold.occluded-std-dev:8.0}")
    private double occludedStdDev;

    /** 参与计算的图像最大边长（缩小采样控制 CPU 占用，子码流本就不大） */
    private static final int MAX_ANALYSIS_WIDTH = 640;

    /**
     * 分析多帧图像，输出异常故障集合。
     *
     * @param frames JPEG 帧列表（建议 3 帧：首帧做单帧指标，帧间差做冻结判定）
     * @return 异常故障集合（无异常返回空集合）
     */
    public Set<VideoFaultType> analyze(List<BufferedImage> frames) {
        Set<VideoFaultType> faults = EnumSet.noneOf(VideoFaultType.class);
        if (frames == null || frames.isEmpty()) {
            return faults;
        }

        BufferedImage first = downscale(frames.get(0));
        int[] rgb = first.getRGB(0, 0, first.getWidth(), first.getHeight(), null, 0, first.getWidth());
        int w = first.getWidth();
        int h = first.getHeight();
        int n = w * h;
        if (n == 0) {
            return faults;
        }

        // ============ 单帧统计 ============
        double sumY = 0, sumY2 = 0;
        double sumR = 0, sumG = 0, sumB = 0;
        double sumSat = 0;
        int darkPixels = 0, brightPixels = 0;
        double[] gray = new double[n];
        double[] laplacian = new double[n];
        double noiseSum = 0;

        for (int i = 0; i < n; i++) {
            int px = rgb[i];
            int r = (px >> 16) & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = px & 0xFF;
            double y = 0.299 * r + 0.587 * g + 0.114 * b;
            gray[i] = y;
            sumY += y;
            sumY2 += y * y;
            sumR += r;
            sumG += g;
            sumB += b;
            int maxC = Math.max(r, Math.max(g, b));
            int minC = Math.min(r, Math.min(g, b));
            sumSat += (maxC - minC);
            if (y < 40) darkPixels++;
            if (y > 215) brightPixels++;
        }

        double meanY = sumY / n;
        double meanR = sumR / n, meanG = sumG / n, meanB = sumB / n;
        double meanSat = sumSat / n;
        double stdY = Math.sqrt(Math.max(0, sumY2 / n - meanY * meanY));

        // ============ 过亮 / 过暗 ============
        double darkRatio = (double) darkPixels / n;
        double brightRatio = (double) brightPixels / n;
        if (meanY < tooDarkMean || (meanY < 50 && darkRatio > darkPixelRatio)) {
            faults.add(VideoFaultType.TOO_DARK);
        } else if (meanY > tooBrightMean || (meanY > 180 && brightRatio > brightPixelRatio)) {
            faults.add(VideoFaultType.TOO_BRIGHT);
        }

        // ============ 偏色 ============
        double rgbDelta = Math.max(Math.abs(meanR - meanG),
                Math.max(Math.abs(meanG - meanB), Math.abs(meanR - meanB)));
        if (rgbDelta > colorCastDelta) {
            faults.add(VideoFaultType.COLOR_CAST);
        }

        // ============ 黑白图像 ============
        if (meanSat < grayscaleSaturation) {
            faults.add(VideoFaultType.GRAYSCALE);
        }

        // ============ 对比度 ============
        if (stdY < contrastStdDev) {
            faults.add(VideoFaultType.LOW_CONTRAST);
        }

        // ============ 模糊（Laplacian 3x3 方差）+ 噪声（邻域残差） ============
        double lapSum = 0, lapSum2 = 0;
        for (int yIdx = 1; yIdx < h - 1; yIdx++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = yIdx * w + x;
                double up = gray[idx - w];
                double down = gray[idx + w];
                double left = gray[idx - 1];
                double right = gray[idx + 1];
                double center = gray[idx];
                double lap = up + down + left + right - 4 * center;
                laplacian[idx] = lap;
                lapSum += lap;
                lapSum2 += lap * lap;

                // 3x3 邻域均值残差（高频噪声估计）
                double localMean = (center + up + down + left + right
                        + gray[idx - w - 1] + gray[idx - w + 1]
                        + gray[idx + w - 1] + gray[idx + w + 1]) / 9.0;
                noiseSum += Math.abs(center - localMean);
            }
        }
        int inner = (w - 2) * (h - 2);
        if (inner > 0) {
            double lapMean = lapSum / inner;
            double lapVar = Math.max(0, lapSum2 / inner - lapMean * lapMean);
            if (lapVar < blurLaplacianVar) {
                faults.add(VideoFaultType.BLUR);
            }
            double noiseResidualAvg = noiseSum / inner;
            if (noiseResidualAvg > noiseResidual) {
                faults.add(VideoFaultType.NOISE);
            }

            // ============ 视频遮挡（边缘极弱 + 画面近似纯色，比模糊更严苛） ============
            if (lapVar < occludedLaplacianVar && stdY < occludedStdDev) {
                faults.add(VideoFaultType.OCCLUDED);
            }
        }

        // ============ 画面冻结（帧间 MAD） ============
        if (frames.size() >= 2) {
            BufferedImage second = downscale(frames.get(frames.size() - 1));
            if (second.getWidth() == w && second.getHeight() == h) {
                int[] rgb2 = second.getRGB(0, 0, w, h, null, 0, w);
                double madSum = 0;
                for (int i = 0; i < n; i++) {
                    int p1 = rgb[i];
                    int p2 = rgb2[i];
                    madSum += Math.abs(((p1 >> 16) & 0xFF) - ((p2 >> 16) & 0xFF))
                            + Math.abs(((p1 >> 8) & 0xFF) - ((p2 >> 8) & 0xFF))
                            + Math.abs((p1 & 0xFF) - (p2 & 0xFF));
                }
                double mad = madSum / n / 3.0;
                if (mad < frozenMad) {
                    faults.add(VideoFaultType.FROZEN);
                }
            }
        }

        if (!faults.isEmpty()) {
            log.debug("[视频告警] 图像指标异常: brightness={}, stdY={}, sat={}, rgbDelta={}",
                    String.format("%.1f", meanY), String.format("%.1f", stdY),
                    String.format("%.1f", meanSat), String.format("%.1f", rgbDelta));
        }
        return faults;
    }

    /** 缩小采样：控制分析 CPU 占用（子码流不超过 640 宽则原样返回） */
    private BufferedImage downscale(BufferedImage src) {
        int w = src.getWidth();
        if (w <= MAX_ANALYSIS_WIDTH) {
            return src;
        }
        int h = (int) Math.round((double) src.getHeight() * MAX_ANALYSIS_WIDTH / w);
        BufferedImage scaled = new BufferedImage(MAX_ANALYSIS_WIDTH, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = scaled.createGraphics();
        try {
            g2d.drawImage(src, 0, 0, MAX_ANALYSIS_WIDTH, h, null);
        } finally {
            g2d.dispose();
        }
        return scaled;
    }
}
