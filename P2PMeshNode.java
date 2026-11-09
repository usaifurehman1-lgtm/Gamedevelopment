import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class P2PMeshNode {
    private static final String TAG = "P2PMeshNode";
    private static final int PORT = 8888;
    
    private String myId;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private List<SocketConnection> activeConnections = new ArrayList<>();
    private OnMessageReceivedListener messageListener;

    public interface OnMessageReceivedListener {
        void onMessageReceived(String senderId, String message);
    }

    public P2PMeshNode(String myId, OnMessageReceivedListener listener) {
        this.myId = myId;
        this.messageListener = listener;
    }

    // سرور شروع کریں تاکہ دوسرے فونز اس سے جڑ سکیں
    public void startServer() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Server started on port " + PORT);
                while (isRunning) {
                    Socket socket = serverSocket.accept();
                    SocketConnection conn = new SocketConnection(socket);
                    conn.start();
                    synchronized (activeConnections) {
                        activeConnections.add(conn);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }).start();
    }

    // کسی دوسرے پیئر (ہاسٹپاٹ آئی پی) سے کنیکٹ ہوں
    public void connectToPeer(String ipAddress) {
        new Thread(() -> {
            try {
                Socket socket = new Socket(ipAddress, PORT);
                SocketConnection conn = new SocketConnection(socket);
                conn.start();
                synchronized (activeConnections) {
                    activeConnections.add(conn);
                }
                Log.d(TAG, "Connected to peer: " + ipAddress);
            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }
        }).start();
    }

    // میسج بھیجنے کا فنکشن (میش نیٹ ورک کے ذریعے فارورڈ کرنا)
    public void sendMessage(String targetId, String message) {
        // فارمیٹ: TARGET_ID:SENDER_ID:MESSAGE
        String fullPacket = targetId + ":" + myId + ":" + message;
        new Thread(() -> {
            synchronized (activeConnections) {
                for (SocketConnection conn : activeConnections) {
                    conn.send(fullPacket);
                }
            }
        }).start();
    }

    // نوڈ بند کرنے کے لیے
    public void stopNode() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
            synchronized (activeConnections) {
                for (SocketConnection conn : activeConnections) {
                    conn.close();
                }
                activeConnections.clear();
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
    }

    // اندرونی کلاس جو ہر ایک کنکشن کو سنبھالتی ہے
    private class SocketConnection extends Thread {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;

        public SocketConnection(Socket socket) {
            this.socket = socket;
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            } catch (Exception e) {
                Log.e(TAG, "Socket init error: " + e.getMessage());
            }
        }

        public void send(String msg) {
            if (out != null) {
                out.println(msg);
            }
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] parts = line.split(":", 3);
                    if (parts.length >= 3) {
                        String targetId = parts[0];
                        String senderId = parts[1];
                        String message = parts[2];

                        if (targetId.equals(myId) || targetId.equals("ALL")) {
                            // اگر میسج ہمارے لیے ہے
                            if (messageListener != null) {
                                messageListener.onMessageReceived(senderId, message);
                            }
                        } else {
                            // اگر میسج کسی اور کے لیے ہے تو اسے آگے ریلے (Mesh Relay) کر دو
                            synchronized (activeConnections) {
                                for (SocketConnection conn : activeConnections) {
                                    conn.send(line);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Read error: " + e.getMessage());
            } finally {
                close();
            }
        }

        public void close() {
            try {
                if (socket != null) socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Close error: " + e.getMessage());
            }
        }
    }
}
