package com.qgyun.hltgq.hltgqdevice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PTZ指令调度器 —— 按通道串行执行 + 过期指令丢弃
 * <p>
 * <b>核心设计：</b>
 * <ul>
 *   <li><b>按通道串行：</b>同一通道的指令放入 FIFO 队列，由单线程依次消费，
 *      消除 CachedThreadPool 并发导致的 START/STOP 乱序风险。</li>
 *   <li><b>过期丢弃：</b>新 START 入队时丢弃同通道未执行的旧 START/STOP（心跳冗余），
 *      新 STOP 入队时丢弃同通道未执行的旧 STOP（去重），确保摄像头"跟手"而非"排队回放"。</li>
 *   <li><b>前端快速返回：</b>Controller 入队后立即返回成功，后台线程异步执行 ICC 调用。</li>
 * </ul>
 *
 * @author hltgq-device
 */
@Slf4j
@Component
public class PtzCommandDispatcher {

    @Resource
    private DahuaPtzService ptzService;

    // ==================== 指令类型 ====================

    /** 指令类别（用于丢弃判断） */
    enum Category { DIRECTION, LENS }

    /** 指令操作 */
    enum Op { START, STOP }

    /** 一条 PTZ 指令 */
    private static class Cmd {
        final Category category;
        final Op op;
        final String channelId;
        // —— 方向类 ——
        final String direction;   // up/down/left/right/...
        final int speed;
        // —— 镜头类 ——
        final String action;      // zoomIn/zoomOut/focusIn/...

        // 方向 START
        static Cmd directStart(String channelId, String direction, int speed) {
            return new Cmd(Category.DIRECTION, Op.START, channelId, direction, speed, null);
        }
        // 方向 STOP
        static Cmd directStop(String channelId, String direction) {
            return new Cmd(Category.DIRECTION, Op.STOP, channelId, direction, 0, null);
        }
        // 镜头 START
        static Cmd lensStart(String channelId, String action, int speed) {
            return new Cmd(Category.LENS, Op.START, channelId, null, speed, action);
        }
        // 镜头 STOP
        static Cmd lensStop(String channelId) {
            return new Cmd(Category.LENS, Op.STOP, channelId, null, 0, null);
        }

        private Cmd(Category category, Op op, String channelId,
                    String direction, int speed, String action) {
            this.category = category;
            this.op = op;
            this.channelId = channelId;
            this.direction = direction;
            this.speed = speed;
            this.action = action;
        }

        @Override
        public String toString() {
            return category + "/" + op + "/" + channelId
                    + (direction != null ? "/" + direction : "")
                    + (action != null ? "/" + action : "");
        }
    }

    // ==================== 按通道队列 ====================

    /** 共享线程池（守护线程，应用关闭时自动终止） */
    private final ExecutorService drainExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ptz-drain");
        t.setDaemon(true);
        return t;
    });

    /** channelId → 通道专属队列 */
    private final ConcurrentHashMap<String, ChannelQueue> channels = new ConcurrentHashMap<>();

    private class ChannelQueue {
        final LinkedList<Cmd> queue = new LinkedList<>();
        final AtomicBoolean draining = new AtomicBoolean(false);
        final String channelId;

        ChannelQueue(String channelId) {
            this.channelId = channelId;
        }

        /**
         * 入队一条指令，自动应用过期丢弃规则，必要时启动消费线程
         */
        void submit(Cmd cmd) {
            synchronized (queue) {
                // ── 过期丢弃规则 ──
                applyDropRules(cmd);
                queue.addLast(cmd);
                log.debug("[PTZ-Q] 入队 {} (队列深度={})", cmd, queue.size());
            }
            // 尝试启动消费
            tryDrain();
        }

        /**
         * 消费循环：逐个执行队列中的指令，直到队列为空
         */
        void drain() {
            while (true) {
                Cmd cmd;
                synchronized (queue) {
                    if (queue.isEmpty()) {
                        draining.set(false);
                        // 双重检查：防止 submit() 在 set(false) 之后但 tryDrain() 之前入队
                        if (queue.isEmpty()) {
                            return; // 安全退出
                        }
                        // 队列非空，继续消费
                        draining.set(true);
                        cmd = queue.removeFirst();
                    } else {
                        cmd = queue.removeFirst();
                    }
                }

                try {
                    execute(cmd);
                } catch (Exception e) {
                    log.error("[PTZ-Q] 指令执行异常：{}", cmd, e);
                }
            }
        }

        private void tryDrain() {
            if (draining.compareAndSet(false, true)) {
                drainExecutor.submit(this::drain);
            }
        }

        /**
         * 过期指令丢弃规则。
         * <p>
         * <b>START 入队时：</b>丢弃同通道、同类别所有未执行指令（包括旧 START + 旧 STOP），
         * 因为新的 START 代表用户最新意图，旧指令已无意义（心跳冗余或方向变更）。
         * <p>
         * <b>STOP 入队时：</b>丢弃同通道、同类别所有未执行的 START 和 STOP，
         * 理由——用户已松手，之前未执行的 START 和多余的 STOP 都不再需要。
         */
        private void applyDropRules(Cmd cmd) {
            Iterator<Cmd> it = queue.iterator();
            int dropped = 0;
            while (it.hasNext()) {
                Cmd pending = it.next();
                // 只丢弃同通道、同类别的指令
                if (!pending.channelId.equals(cmd.channelId)
                        || pending.category != cmd.category) {
                    continue;
                }

                boolean shouldDrop;
                if (cmd.op == Op.START) {
                    // 新 START 丢弃所有同类别未执行指令（心跳冗余 + 换方向）
                    shouldDrop = true;
                } else {
                    // 新 STOP 丢弃所有同类别未执行指令（松手后旧 START/STOP 都无意义）
                    shouldDrop = true;
                }

                if (shouldDrop) {
                    it.remove();
                    dropped++;
                }
            }
            if (dropped > 0) {
                log.info("[PTZ-Q] {} 丢弃 {} 条过期指令（channelId={}）",
                        cmd, dropped, cmd.channelId);
            }
        }

        private void execute(Cmd cmd) {
            log.info("[PTZ-Q] 执行 {}", cmd);
            if (cmd.category == Category.DIRECTION) {
                if (cmd.op == Op.START) {
                    ptzService.operateDirect(cmd.channelId, cmd.direction, cmd.speed);
                } else {
                    ptzService.stopDirect(cmd.channelId, cmd.direction);
                }
            } else {
                if (cmd.op == Op.START) {
                    ptzService.operateLens(cmd.channelId, cmd.action, cmd.speed);
                } else {
                    ptzService.operateLens(cmd.channelId, "stop", 0);
                }
            }
        }
    }

    // ==================== 公开 API ====================

    /** 提交方向 START 指令，前端立即返回，后台串行执行 */
    public void submitDirectStart(String channelId, String direction, int speed) {
        channelQueue(channelId).submit(Cmd.directStart(channelId, direction, speed));
    }

    /** 提交方向 STOP 指令 */
    public void submitDirectStop(String channelId, String direction) {
        channelQueue(channelId).submit(Cmd.directStop(channelId, direction));
    }

    /** 提交镜头 START 指令 */
    public void submitLensStart(String channelId, String action, int speed) {
        channelQueue(channelId).submit(Cmd.lensStart(channelId, action, speed));
    }

    /** 提交镜头 STOP 指令 */
    public void submitLensStop(String channelId) {
        channelQueue(channelId).submit(Cmd.lensStop(channelId));
    }

    private ChannelQueue channelQueue(String channelId) {
        return channels.computeIfAbsent(channelId, ChannelQueue::new);
    }

    @PreDestroy
    public void shutdown() {
        log.info("PTZ指令调度器关闭，当前队列数：{}", channels.size());
        drainExecutor.shutdown();
    }
}
