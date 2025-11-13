import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;

public class ChatServer {
  private static final int BUFFER_SIZE = 16 * 1024;

  // Per-connection state
  private static class Connection {
    final ByteBuffer in = ByteBuffer.allocate(BUFFER_SIZE);
    final Queue<ByteBuffer> outQueue = new ArrayDeque<>();
    final StringBuilder recv = new StringBuilder(); // accumulate partial text
    String nick = null; // pseudonym (first line sent by client)
  }

  public static void main(String[] args) throws Exception {
    final int port;
    if (args.length == 0) {
      port = 8000;
      System.out.println("No port given, using default: " + port);
    } else {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        System.err.println("Invalid port: " + args[0]);
        return;
      }
    }

    ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.bind(new InetSocketAddress(port));

    Selector selector = Selector.open();
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    System.out.println("Listening on port " + port);

    while (true) {
      selector.select(); // block until at least one channel is ready
      Set<SelectionKey> selected = selector.selectedKeys();
      Iterator<SelectionKey> it = selected.iterator();
      while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();

        try {
          if (key.isAcceptable()) {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel sc = ssc.accept(); // non-blocking accept()
            if (sc == null) continue;
            sc.configureBlocking(false);
            Connection conn = new Connection();
            sc.register(selector, SelectionKey.OP_READ, conn);
            System.out.println("Accepted connection from " + sc.getRemoteAddress());
          }

          if (key.isReadable()) {
            SocketChannel sc = (SocketChannel) key.channel();
            Connection conn = (Connection) key.attachment();
            boolean alive = handleRead(sc, conn, key);
            if (!alive) {
              // client closed
              key.cancel();
              try { sc.close(); } catch (IOException ignore) {}
              System.out.println(conn.nick + " has left the chat.");
            }
          }

          if (key.isWritable()) {
            SocketChannel sc = (SocketChannel) key.channel();
            Connection conn = (Connection) key.attachment();
            handleWrite(sc, conn, key);
          }
        } catch (CancelledKeyException cke) {
          // key was cancelled elsewhere; ignore
        } catch (IOException ioe) {
          // error on this channel; cancel and close
          try {
            key.cancel();
            if (key.channel() != null) key.channel().close();
          } catch (IOException ignore) {}
          System.err.println("IO error: " + ioe.getMessage());
        }
      }
    }
  }

  // Returns false if the connection should be closed
  private static boolean handleRead(SocketChannel sc, Connection conn, SelectionKey key) throws IOException {
    ByteBuffer in = conn.in;
    int bytesRead = sc.read(in);
    if (bytesRead == -1) {
        String leaveMsg = conn.nick + " left the chat.\n";
        broadcast(leaveMsg, key);
      return false; // client closed
    }
    if (bytesRead == 0) {
      return true; // nothing read now
    }

    // Prepare buffer to read the bytes just received
    in.flip();

    // Copy bytes to a byte[] and decode as UTF-8 fragment
    byte[] data = new byte[in.remaining()];
    in.get(data);
    in.clear();

    String chunk = new String(data, StandardCharsets.UTF_8);
    conn.recv.append(chunk);

    // Process complete lines (delimited by '\n'); support '\r\n' as well
    while (true) {
      int idx = -1;
      for (int i = 0; i < conn.recv.length(); i++) {
        if (conn.recv.charAt(i) == '\n') { idx = i; break; }
      }
      if (idx == -1) break; // no full line yet

      // Extract the line without the trailing newline and optional '\r'
      String line = conn.recv.substring(0, idx);
      if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);

      // Remove the processed line from the buffer
      conn.recv.delete(0, idx + 1);

      if (conn.nick == null) {
        // First line is the nickname
        conn.nick = line.trim();
        System.out.println("Set nickname for " + sc.getRemoteAddress() + " -> " + conn.nick);
        // Optionally notify others that someone joined:
        String joinMsg = conn.nick + " joined the chat.\n";
        broadcast(joinMsg, key);
      } else {
        // Regular message: broadcast "nick: message"
        String msg = conn.nick + ": " + line + "\n";
        System.out.println("Broadcasting: " + msg.trim());
        broadcast(msg, key);
      }
    }

    return true;
  }

  // Broadcast message (UTF-8 string) to all connected clients.
  // The 'originKey' parameter is the SelectionKey of the sender (may be used to exclude sender if desired).
  private static void broadcast(String message, SelectionKey originKey) {
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    Selector sel = originKey.selector();
    for (SelectionKey k : sel.keys()) {
      if (!k.isValid()) continue;
      if (!(k.channel() instanceof SocketChannel)) continue;
      Connection other = (Connection) k.attachment();
      if (other == null) continue; // skip server socket
      // Enqueue a new ByteBuffer for this client
      ByteBuffer outBuf = ByteBuffer.wrap(payload);
      other.outQueue.add(outBuf);
      try {
        // Try to write immediately
        handleWrite((SocketChannel) k.channel(), other, k);
      } catch (IOException e) {
        // On write error, cancel and close the key/channel
        try { k.cancel(); if (k.channel() != null) k.channel().close(); } catch (IOException ignore) {}
      }
    }
  }

  private static void handleWrite(SocketChannel sc, Connection conn, SelectionKey key) throws IOException {
    Queue<ByteBuffer> q = conn.outQueue;
    while (!q.isEmpty()) {
      ByteBuffer buf = q.peek();
      sc.write(buf);
      if (buf.hasRemaining()) {
        // Partial write: ensure OP_WRITE is registered so we finish later
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        return;
      } else {
        // Fully written: remove and continue with next
        q.poll();
      }
    }
    // Nothing left to write: remove write interest
    if ((key.interestOps() & SelectionKey.OP_WRITE) != 0) {
      key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }
  }
}