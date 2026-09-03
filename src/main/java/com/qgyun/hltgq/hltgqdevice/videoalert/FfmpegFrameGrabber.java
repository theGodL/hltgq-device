package com.qgyun.hltgq.hltgqdevice.videoalert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 抽帧工具：从 NVR HLS 流 URL 抓取若干 JPEG 帧，供图像质量分析。
 * <p>命令：ffmpeg -y -rw_timeout 8s -i {流地址} -an -vf scale={W}:-2 -f image2 -frames:v N -q:v 2 {tmpdir}/f%02d.jpg
 * <ul>
 *   <li>抽满 N 帧即自动退出，不会持续拉流占用带宽；</li>
 *   <li>整体硬超时（默认 15s）强杀进程，防止僵尸 ffmpeg；</li>
 *   <li>降采样至 scaleWidth 宽（默认 320，保持宽高比、偶数高度）：B 层图像指标均为
 *       统计量（亮度/饱和度/Laplacian/对比度/帧差），对分辨率不敏感，降采样显著降低 CPU/内存；
 *       配置为 0 时不缩放；</li>
 *   <li>流地址中的 "$" 需编码为 %24（NVR 通道ID 含 $，与 DahuaVideoService 探测逻辑一致）；</li>
 *   <li>临时目录用后即删。</li>
 * </ul>
 */
@Slf4j
@Component
public class FfmpegFrameGrabber {

    /** ffmpeg 拉流读超时（微秒，8s），流不可达时快速失败而非无限等待 */
    private static final String READ_TIMEOUT_US = "8000000";

    /** 抽帧降采样宽度（0=不缩放；高度按宽高比取偶数，配置项 video-alert.frame-scale-width） */
    @Value("${video-alert.frame-scale-width:320}")
    private int scaleWidth;

    /**
     * 从流地址抓取 JPEG 帧。
     *
     * @param streamUrl       NVR 流地址（含 token，子码流）
     * @param maxFrames       期望帧数（1~10）
     * @param timeoutSeconds  整体超时（秒），超时强杀进程
     * @return 帧列表（可能少于 maxFrames）；完全失败返回空列表
     */
    public List<BufferedImage> grabFrames(String streamUrl, int maxFrames, int timeoutSeconds) {
        if (streamUrl == null || streamUrl.trim().isEmpty() || maxFrames < 1) {
            return Collections.emptyList();
        }
        int frames = Math.min(maxFrames, 10);
        Path tmpDir = null;
        Process process = null;
        try {
            tmpDir = Files.createTempDirectory("videoalert-frames");
            String url = streamUrl.replace("$", "%24");
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-y");
            cmd.add("-rw_timeout");
            cmd.add(READ_TIMEOUT_US);
            cmd.add("-i");
            cmd.add(url);
            cmd.add("-an");
            // 降采样（B 层指标为统计量，分辨率不敏感）：降低解码/缩放 CPU 与内存占用
            if (scaleWidth > 0) {
                cmd.add("-vf");
                cmd.add("scale=" + scaleWidth + ":-2");
            }
            cmd.add("-f");
            cmd.add("image2");
            cmd.add("-frames:v");
            cmd.add(String.valueOf(frames));
            cmd.add("-q:v");
            cmd.add("2");
            cmd.add(tmpDir.toString() + "/f%02d.jpg");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            // 后台消费 stdout/stderr，防止管道写满阻塞 ffmpeg
            final InputStream procIn = process.getInputStream();
            Thread drain = new Thread(() -> drain(procIn), "ffmpeg-frame-drain");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[视频告警] ffmpeg 抽帧超时 {}s，强杀进程: {}", timeoutSeconds, streamUrl);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }

            List<BufferedImage> images = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmpDir, "*.jpg")) {
                for (Path p : ds) {
                    try {
                        BufferedImage img = ImageIO.read(p.toFile());
                        if (img != null) {
                            images.add(img);
                        }
                    } catch (IOException e) {
                        log.debug("[视频告警] 帧文件读取失败: {}", p.getFileName());
                    }
                }
            }
            log.debug("[视频告警] ffmpeg 抽帧结果: 期望{}张, 实得{}张", frames, images.size());
            return images;
        } catch (Exception e) {
            log.warn("[视频告警] ffmpeg 抽帧失败: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (tmpDir != null) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmpDir, "*")) {
                    for (Path p : ds) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                }
                try {
                    Files.deleteIfExists(tmpDir);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 静默消费子进程输出流，防止管道阻塞 */
    private void drain(InputStream in) {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
        } catch (IOException ignored) {
        }
    }
}
