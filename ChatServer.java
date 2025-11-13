import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;

public class ChatServer {
  private static final int BUFFER_SIZE = 16 * 1024;
  // key : nickname, value: connection (easier to find out if the nickname already
  // exists)
  private static final HashMap<String, Connection> nicknameMap = new HashMap<>();
  private static Selector selector;
  // ERROR msg
  private static final String ERROR = "ERROR\n";
  // OK msg
  private static final String OK = "OK\n";

  enum State {
    INIT, INSIDE, OUTSIDE
  };

  // Per-connection state
  private static class Connection {
    final ByteBuffer in = ByteBuffer.allocate(BUFFER_SIZE);
    final Queue<ByteBuffer> outQueue = new ArrayDeque<>();
    final StringBuilder recv = new StringBuilder(); // accumulate partial text
    String nick = null; // pseudonym (first line sent by client)
    String room = null; // room name
    State state = State.INIT; // state of connection: init, outside, inside
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

    selector = Selector.open();
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
            if (sc == null)
              continue;
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
              try {
                sc.close();
              } catch (IOException ignore) {
              }
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
            if (key.channel() != null)
              key.channel().close();
          } catch (IOException ignore) {
          }
          System.err.println("IO error: " + ioe.getMessage());
        }
      }
    }
  }

  // Returns false if the connection should be closed
  private static boolean handleRead(SocketChannel sc, Connection conn, SelectionKey key) throws IOException {
    ByteBuffer in = conn.in;
    int bytesRead = sc.read(in);
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
        if (conn.recv.charAt(i) == '\n') {
          idx = i;
          break;
        }
      }
      if (idx == -1)
        break; // no full line yet

      // Extract the line without the trailing newline and optional '\r'
      String line = conn.recv.substring(0, idx);
      // check if it's empty
      if (line.isEmpty())
        return true;
      if (line.endsWith("\r"))
        line = line.substring(0, line.length() - 1);

      // Remove the processed line from the buffer
      conn.recv.delete(0, idx + 1);
      // if the user is in init state and trying cmds that aren't /nick, ERROR
      // does the line start with '/'?
      if (line.startsWith("/")) {
        if (line.length() > 1 && line.charAt(1) == '/') {
          // it's just a message that starts with //
          String escapedMessage = line.substring(1);
          // Regular message: broadcast "nick: message"
          String RegularMsg = "MESSAGE " + conn.nick +  " " + escapedMessage + "\n";
          System.out.println("Broadcasting: " + RegularMsg.trim());
          if (conn.state == State.INSIDE) {
            broadcast(RegularMsg, conn.room, null);
          }
        } else {
          String[] parts = line.split(" ");
          String cmd = parts[0];
          switch (cmd) {
            case "/nick" -> {
                // used to pick a nickname or to change a nickname (the nickname can't already
                // be chosen by another user!)
                // change states from init to outside if they are in initialization, if they are just changing the name and already outside or inside, the state doesn't change
                String new_nickname = parts[1];
                // check if nickname already exists
                if (nicknameMap.containsKey(new_nickname)) {
                    send(conn, key, ERROR);
                } else {
                    if (conn.nick != null) { // if it's a nickname change instead of user creation
                        nicknameMap.remove(conn.nick);
                        String NewNickMsg = "NEWNICK " + conn.nick + " " + new_nickname + "\n";
                        broadcast(NewNickMsg, conn.room, key);
                    }
                    conn.nick = new_nickname;
                    nicknameMap.put((new_nickname), conn);
                    send(conn, key, OK);
                } }
            case "/join" -> {
                // if the user is already in a room: LEFT , then JOINED, change state to inside if they weren't before
                String JoinedMsg = "JOINED " + conn.nick + "\n";
                if (conn.state == State.INSIDE) {
                    broadcast(JoinedMsg, conn.room, key); // update new room!
                } }
            case "/priv" -> {
              // /priv name msg
              }
            case "/leave" -> {
                // change room to null
                // change state to outside
                String LeftMsg = "LEFT " + conn.nick + "\n";
                if (conn.state == State.INSIDE) {
                    broadcast(LeftMsg, conn.room, key);
                }
                send(conn, key, OK);
                }
            case "/bye" -> {
                String ByeMsg = "BYE\n";
                    String LeftMsg = "LEFT " + conn.nick + "\n";
                    if (conn.state == State.INSIDE) {
                        broadcast(LeftMsg, conn.room, key);
                    }     send(conn, key, ByeMsg);
                    // close connection
                }
            default -> {
                }
          }
            // erro!
                    }
      } else {
        // Regular message: broadcast "nick: message"
        String RegularMsg = "MESSAGE " + conn.nick + " " + line + "\n";
        System.out.println("Broadcasting: " + RegularMsg.trim());
        if (conn.state == State.INSIDE) {
          broadcast(RegularMsg, conn.room, null);
        }
      }

    }
    return true;

  }

  // Broadcast message (UTF-8 string) to all connected clients. (Multicast)
  // The text is broadcasted to a specific room
  // The 'originKey' parameter is the SelectionKey of the sender (may be used to
  // exclude sender if desired).
  // Basically if key is null, sends to everyone, if key isn't null, sends it to
  // everyone but the sender
  private static void broadcast(String message, String room, SelectionKey originKey) {
    if (!message.endsWith("\n"))
      message += "\n";
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    // if originKey is null, the loop runs normally using the global selector and k == originKey is false, so it sends it to everyone
    // if it exists, it works the same but k == originKey is true so it skips k

    for (SelectionKey k : selector.keys()) {
      if (!k.isValid())
        continue;
      if (!(k.channel() instanceof SocketChannel))
        continue;
      Connection target = (Connection) k.attachment();
      if (target == null || target.room == null)
        continue; // skip server socket
      if (!target.room.equals(room))
        continue;
      if (k == originKey)
        continue;
      // Enqueue a new ByteBuffer for this client
      ByteBuffer outBuf = ByteBuffer.wrap(payload);
      target.outQueue.add(outBuf);
      k.interestOps(k.interestOps() | SelectionKey.OP_WRITE);
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
  // function to Unicast (send a msg to a unique client) -> to send OK and ERROR regarding the client requests to the server
  private static void send(Connection conn, SelectionKey key, String message) {
    if (!message.endsWith("\n"))
      message += "\n"; // just in case
    byte[] data = message.getBytes(StandardCharsets.UTF_8); // converts strings to bytes
    ByteBuffer buffer = ByteBuffer.wrap(data); // puts them in a buffer
    conn.outQueue.add(buffer); // adds buffer to waiting queue of the connection
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE); // warns the selector that it wants to write
  }
}