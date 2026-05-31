import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.*;

public class SRVisualizer extends JFrame {
    private static final int WINDOW_SIZE = 4;
    private static final int MAX_SEQUENCE = 16;
    private static final int PACKET_WIDTH = 40;
    private static final int PACKET_HEIGHT = 30;
    
    // 发送方状态
    private int sendBase = 0;
    private int nextSeqNum = 0;
    private boolean[] sentPackets = new boolean[MAX_SEQUENCE];
    private boolean[] ackedPackets = new boolean[MAX_SEQUENCE];
    
    // 每个分组的独立计时器
    private volatile int[] timerValues = new int[MAX_SEQUENCE];
    private volatile boolean[] timerRunning = new boolean[MAX_SEQUENCE];
    private Timer[] packetTimers = new Timer[MAX_SEQUENCE];
    
    // 接收方状态
    private int rcvBase = 0;
    private boolean[] receivedPackets = new boolean[MAX_SEQUENCE];
    private boolean[] outOfOrderPackets = new boolean[MAX_SEQUENCE];
    
    // 动画系统 - 支持多个并发动画
    private List<PacketAnimation> activeAnimations = Collections.synchronizedList(new ArrayList<>());
    
    // 场景控制
    private int scenarioType = 0;
    private int packetIndex = 0;
    private int lossCount = 0;
    private int totalPacketsToSend = 5;
    private boolean packet3AckLostOnce = false; // 标记分组3的ACK是否已丢失过一次
    
    // 界面组件
    private JPanel senderPanel;
    private JPanel receiverPanel;
    private JPanel controlPanel;
    private JButton normalButton;
    private JButton lossButton;
    private JLabel statusLabel;
    private Timer sendScheduler;
    
    // 动画类 - 封装单个分组的传输动画
    private class PacketAnimation {
        int packetNum;
        int x, y;
        Color color;
        boolean isAck;
        Timer timer;
        volatile boolean active = true;
        
        PacketAnimation(int packetNum, int x, int startY, Color color, boolean isAck) {
            this.packetNum = packetNum;
            this.x = x;
            this.y = startY;
            this.color = color;
            this.isAck = isAck;
        }
        
        void start(final Runnable onComplete) {
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (!active) return;
                    
                    if (isAck) {
                        y -= 4; // ACK向上移动（向发送方）
                        if (y <= -250) { // 到达发送方
                            finish();
                            if (onComplete != null) onComplete.run();
                        }
                    } else {
                        y += 4; // 分组向下移动（向接收方）
                        if (y >= 300) { // 到达接收方
                            finish();
                            if (onComplete != null) onComplete.run();
                        }
                    }
                    
                    repaintPanels();
                }
            }, 50, 50);
        }
        
        void finish() {
            active = false;
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            synchronized (activeAnimations) {
                activeAnimations.remove(this);
            }
            repaintPanels();
        }
        
        void cancel() {
            active = false;
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            synchronized (activeAnimations) {
                activeAnimations.remove(this);
            }
        }
    }
    
    public SRVisualizer() {
        setTitle("SR选择重传协议可视化模拟");
        setSize(800, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        
        senderPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawSenderWindow(g);
            }
        };
        senderPanel.setPreferredSize(new Dimension(800, 250));
        senderPanel.setBackground(Color.WHITE);
        
        receiverPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawReceiverWindow(g);
            }
        };
        receiverPanel.setPreferredSize(new Dimension(800, 250));
        receiverPanel.setBackground(Color.WHITE);
        
        controlPanel = new JPanel();
        controlPanel.setLayout(new FlowLayout());
        
        normalButton = new JButton("正常发包");
        lossButton = new JButton("丢包");
        statusLabel = new JLabel("请选择场景开始模拟");
        
        controlPanel.add(normalButton);
        controlPanel.add(lossButton);
        controlPanel.add(statusLabel);
        
        normalButton.addActionListener(e -> startNormalScenario());
        lossButton.addActionListener(e -> startLossScenario());
        
        add(senderPanel, BorderLayout.NORTH);
        add(receiverPanel, BorderLayout.CENTER);
        add(controlPanel, BorderLayout.SOUTH);
        
        resetState();
    }
    
    private void resetState() {
        sendBase = 0;
        nextSeqNum = 0;
        rcvBase = 0;
        packetIndex = 0;
        lossCount = 0;
        scenarioType = 0;
        packet3AckLostOnce = false; // 重置ACK丢失标志
        
        stopAllTimers();
        
        if (sendScheduler != null) {
            sendScheduler.cancel();
            sendScheduler = null;
        }
        
        synchronized (activeAnimations) {
            for (PacketAnimation anim : activeAnimations) {
                anim.cancel();
            }
            activeAnimations.clear();
        }
        
        for (int i = 0; i < MAX_SEQUENCE; i++) {
            sentPackets[i] = false;
            ackedPackets[i] = false;
            receivedPackets[i] = false;
            outOfOrderPackets[i] = false;
            timerValues[i] = 7;
            timerRunning[i] = false;
            if (packetTimers[i] != null) {
                packetTimers[i].cancel();
                packetTimers[i] = null;
            }
        }
        
        statusLabel.setText("请选择场景开始模拟");
        senderPanel.repaint();
        receiverPanel.repaint();
    }
    
    private void stopAllTimers() {
        for (int i = 0; i < MAX_SEQUENCE; i++) {
            if (packetTimers[i] != null) {
                packetTimers[i].cancel();
                packetTimers[i] = null;
            }
            timerRunning[i] = false;
        }
    }
    
    private void repaintPanels() {
        SwingUtilities.invokeLater(() -> {
            senderPanel.repaint();
            receiverPanel.repaint();
        });
    }
    
    private void drawSenderWindow(Graphics g) {
        int startX = 50;
        int startY = 50;
        
        g.setColor(Color.BLACK);
        g.setFont(new Font("宋体", Font.BOLD, 16));
        g.drawString("发送方 (SR协议)", startX, startY - 10);
        
        for (int i = 0; i < MAX_SEQUENCE; i++) {
            int x = startX + i * PACKET_WIDTH;
            int y = startY;
            
            Color color;
            if (ackedPackets[i]) {
                color = Color.GREEN;
            } else if (sentPackets[i]) {
                color = Color.YELLOW;
            } else if (i >= sendBase && i < sendBase + WINDOW_SIZE) {
                color = Color.BLUE;
            } else {
                color = Color.LIGHT_GRAY;
            }
            
            g.setColor(color);
            g.fillRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
            g.setColor(Color.BLACK);
            g.drawRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
            g.drawString(String.valueOf(i), x + PACKET_WIDTH/2 - 5, y + PACKET_HEIGHT/2 + 5);
        }
        
        // 绘制窗口
        int sendWindowEnd = Math.min(sendBase + WINDOW_SIZE, MAX_SEQUENCE);
        if (sendBase < sendWindowEnd) {
            int windowX = startX + sendBase * PACKET_WIDTH;
            int windowY = startY - 5;
            int windowWidth = Math.min(WINDOW_SIZE, sendWindowEnd - sendBase) * PACKET_WIDTH;
            
            g.setColor(Color.RED);
            g.drawRect(windowX, windowY, windowWidth, PACKET_HEIGHT + 10);
        }
        
        // 绘制计时器
        for (int i = 0; i < MAX_SEQUENCE; i++) {
            if (timerRunning[i]) {
                int timerX = startX + i * PACKET_WIDTH - 20;
                int timerY = startY + PACKET_HEIGHT + 20;
                
                float ratio = (float) timerValues[i] / 7.0f;
                int red = (int) (255 * (1 - ratio));
                int green = (int) (255 * ratio);
                
                g.setColor(new Color(red, green, 0));
                g.fillOval(timerX, timerY, 30, 30);
                g.setColor(Color.BLACK);
                g.drawOval(timerX, timerY, 30, 30);
                g.drawString(String.valueOf(timerValues[i]), timerX + 10, timerY + 20);
            }
        }
        
        g.setColor(Color.BLACK);
        g.setFont(new Font("宋体", Font.PLAIN, 12));
        g.drawString("send_base: " + sendBase, startX, startY + PACKET_HEIGHT + 60);
        g.drawString("nextseqnum: " + nextSeqNum, startX + 100, startY + PACKET_HEIGHT + 60);
        g.drawString("窗口大小: " + WINDOW_SIZE, startX + 200, startY + PACKET_HEIGHT + 60);
        
        // 绘制动画 - 分组向下移动 + ACK返回时在发送方面板显示
        synchronized (activeAnimations) {
            for (PacketAnimation anim : activeAnimations) {
                if (!anim.isAck && anim.y > startY && anim.y < 300) {
                    drawAnimationRect(g, anim.x, anim.y, anim.packetNum, anim.color);
                } else if (anim.isAck && anim.y < 0) {
                    drawAnimationRect(g, anim.x, anim.y + 300, anim.packetNum, anim.color);
                }
            }
        }
    }
    
    private void drawReceiverWindow(Graphics g) {
        int startX = 50;
        int startY = 50;
        
        g.setColor(Color.BLACK);
        g.setFont(new Font("宋体", Font.BOLD, 16));
        g.drawString("接收方 (SR协议)", startX, startY - 10);
        
        for (int i = 0; i < MAX_SEQUENCE; i++) {
            int x = startX + i * PACKET_WIDTH;
            int y = startY;
            
            Color color;
            if (i < rcvBase) {
                color = Color.GREEN;
            } else if (outOfOrderPackets[i]) {
                color = Color.RED;
            } else if (i >= rcvBase && i < rcvBase + WINDOW_SIZE) {
                color = Color.BLUE;
            } else {
                color = Color.LIGHT_GRAY;
            }
            
            g.setColor(color);
            g.fillRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
            g.setColor(Color.BLACK);
            g.drawRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
            g.drawString(String.valueOf(i), x + PACKET_WIDTH/2 - 5, y + PACKET_HEIGHT/2 + 5);
        }
        
        int rcvWindowEnd = Math.min(rcvBase + WINDOW_SIZE, MAX_SEQUENCE);
        if (rcvBase < rcvWindowEnd) {
            int windowX = startX + rcvBase * PACKET_WIDTH;
            int windowY = startY - 5;
            int windowWidth = Math.min(WINDOW_SIZE, rcvWindowEnd - rcvBase) * PACKET_WIDTH;
            
            g.setColor(Color.RED);
            g.drawRect(windowX, windowY, windowWidth, PACKET_HEIGHT + 10);
        }
        
        g.setColor(Color.BLACK);
        g.setFont(new Font("宋体", Font.PLAIN, 12));
        g.drawString("rcv_base: " + rcvBase, startX, startY + PACKET_HEIGHT + 40);
        g.drawString("窗口大小: " + WINDOW_SIZE, startX + 100, startY + PACKET_HEIGHT + 40);
        
        // 绘制动画 - 分组到达前 + ACK起始阶段
        synchronized (activeAnimations) {
            for (PacketAnimation anim : activeAnimations) {
                if (!anim.isAck && anim.y >= 300) {
                    // 分组到达接收方附近
                } else if (anim.isAck && anim.y >= 85 && anim.y < 300) {
                    drawAnimationRect(g, anim.x, anim.y, anim.packetNum, anim.color);
                }
            }
        }
    }
    
    private void drawAnimationRect(Graphics g, int x, int y, int num, Color color) {
        if (y < 0 || y > 300) return;
        g.setColor(color);
        g.fillRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
        g.setColor(Color.BLACK);
        g.drawRect(x, y, PACKET_WIDTH - 2, PACKET_HEIGHT);
        if (num >= 0) {
            g.drawString(String.valueOf(num), x + PACKET_WIDTH/2 - 5, y + PACKET_HEIGHT/2 + 5);
        }
    }
    
    private void startNormalScenario() {
        resetState();
        scenarioType = 1;
        statusLabel.setText("正常发包场景开始 - 每隔1秒发送一个分组");
        startSendingPackets();
    }
    
    private void startLossScenario() {
        resetState();
        scenarioType = 2;
        lossCount = 0;
        statusLabel.setText("丢包场景开始 - 每隔1秒发送一个分组");
        startSendingPackets();
    }
    
    private void startSendingPackets() {
        if (sendScheduler != null) {
            sendScheduler.cancel();
        }
        sendScheduler = new Timer();
        sendScheduler.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendNextPacket();
            }
        }, 0, 1000);
    }
    
    private void sendNextPacket() {
        if (packetIndex >= totalPacketsToSend) {
            statusLabel.setText("所有分组已发送完成");
            if (sendScheduler != null) {
                sendScheduler.cancel();
                sendScheduler = null;
            }
            return;
        }
        
        if (nextSeqNum < sendBase + WINDOW_SIZE && nextSeqNum < MAX_SEQUENCE) {
            int packetNum = nextSeqNum;
            
            boolean shouldLose = false;
            if (scenarioType == 2 && lossCount < 2) {
                if (lossCount == 0 && packetNum == 1) {
                    shouldLose = true;
                } else if (lossCount == 1 && packetNum == 3) {
                    shouldLose = true;
                }
            }
            
            if (shouldLose) {
                lossCount++;
                if (packetNum == 1) {
                    statusLabel.setText("丢包: 分组 " + packetNum + " 丢失（将超时重传）");
                    sentPackets[packetNum] = true;
                    nextSeqNum++;
                    packetIndex++;
                    startTimerForPacket(packetNum);
                    repaintPanels();
                } else if (packetNum == 3) {
                    statusLabel.setText("发送分组 " + packetNum + "（ACK将丢失）");
                    animatePacketSend(packetNum);
                }
            } else {
                statusLabel.setText("发送分组 " + packetNum);
                animatePacketSend(packetNum);
            }
        } else {
            statusLabel.setText("发送窗口已满，等待ACK确认");
        }
    }
    
    private void animatePacketSend(int packetNum) {
        int startX = 50;
        int startY = 50;
        
        boolean isNewPacket = !sentPackets[packetNum];
        sentPackets[packetNum] = true;
        
        if (isNewPacket) {
            nextSeqNum++;
            packetIndex++;
        }
        
        startTimerForPacket(packetNum);
        
        PacketAnimation animation = new PacketAnimation(
            packetNum,
            startX + packetNum * PACKET_WIDTH,
            startY + PACKET_HEIGHT + 10,
            Color.YELLOW,
            false
        );
        
        synchronized (activeAnimations) {
            activeAnimations.add(animation);
        }
        
        animation.start(() -> handlePacketReceive(packetNum));
        repaintPanels();
    }
    
    private void handlePacketReceive(int packetNum) {
        boolean isDuplicate = false;
        
        if (packetNum < rcvBase || receivedPackets[packetNum]) {
            isDuplicate = true;
            statusLabel.setText("收到重复分组 " + packetNum + "，重新发送ACK");
        }
        
        if (!isDuplicate) {
            if (packetNum == rcvBase) {
                receivedPackets[packetNum] = true;
                rcvBase++;
                while (rcvBase < MAX_SEQUENCE && outOfOrderPackets[rcvBase]) {
                    outOfOrderPackets[rcvBase] = false;
                    rcvBase++;
                }
                statusLabel.setText("按序接收分组 " + packetNum);
            } else if (packetNum > rcvBase && packetNum < rcvBase + WINDOW_SIZE) {
                receivedPackets[packetNum] = true;
                outOfOrderPackets[packetNum] = true;
                statusLabel.setText("失序接收分组 " + packetNum + "（缓存中）");
            }
        }
        
        repaintPanels();
        
        boolean shouldSendAck = true;
        if (scenarioType == 2 && packetNum == 3 && lossCount >= 2 && !packet3AckLostOnce) {
            shouldSendAck = false;
            packet3AckLostOnce = true; // 标记已丢失过一次
            statusLabel.setText("分组 " + packetNum + " 的ACK丢失（模拟ACK丢失）");
        }
        
        if (shouldSendAck) {
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    animateAckSend(packetNum);
                }
            }, 500);
        }
    }
    
    private void animateAckSend(int packetNum) {
        int startX = 50;
        int startY = 50;
        
        PacketAnimation animation = new PacketAnimation(
            packetNum,
            startX + packetNum * PACKET_WIDTH,
            startY + PACKET_HEIGHT + 10,
            Color.GREEN,
            true
        );
        
        synchronized (activeAnimations) {
            activeAnimations.add(animation);
        }
        
        animation.start(() -> {
            ackedPackets[packetNum] = true;
            stopTimerForPacket(packetNum);
            updateSendBase();
            statusLabel.setText("收到ACK " + packetNum);
            repaintPanels();
        });
        
        statusLabel.setText("发送ACK " + packetNum);
        repaintPanels();
    }
    
    private void updateSendBase() {
        while (sendBase < MAX_SEQUENCE && ackedPackets[sendBase]) {
            sendBase++;
        }
    }
    
    private void startTimerForPacket(int packetNum) {
        stopTimerForPacket(packetNum);
        
        timerRunning[packetNum] = true;
        timerValues[packetNum] = 7;
        
        packetTimers[packetNum] = new Timer();
        packetTimers[packetNum].scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (timerValues[packetNum] > 0) {
                    timerValues[packetNum]--;
                    SwingUtilities.invokeLater(() -> senderPanel.repaint());
                } else {
                    stopTimerForPacket(packetNum);
                    if (!ackedPackets[packetNum]) {
                        handleTimeout(packetNum);
                    }
                }
            }
        }, 1000, 1000);
    }
    
    private void stopTimerForPacket(int packetNum) {
        if (packetTimers[packetNum] != null) {
            packetTimers[packetNum].cancel();
            packetTimers[packetNum] = null;
        }
        timerRunning[packetNum] = false;
    }
    
    private void handleTimeout(int packetNum) {
        statusLabel.setText("超时重传分组 " + packetNum);
        animatePacketSend(packetNum);
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SRVisualizer().setVisible(true));
    }
}