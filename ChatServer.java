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
  // Bye msg
  private static final String BYE = "BYE\n";

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
    boolean closeAfterWrite = false;
    SelectionKey key; // storing own key
  }

  public static void main(String[] args) throws Exception {
    final int port;
    if (args.length == 0) {
      port = 8000;
    } else {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e) {
        return;
      }
    }

    ServerSocketChannel serverChannel = ServerSocketChannel.open();
    serverChannel.configureBlocking(false);
    serverChannel.bind(new InetSocketAddress(port));

    selector = Selector.open();
    serverChannel.register(selector, SelectionKey.OP_ACCEPT);

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
            SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ, conn);
            conn.key = clientKey;
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
            SelectableChannel ch = key.channel();
            Connection conn = (Connection) key.attachment();
            if (ch instanceof SocketChannel) {
              SocketChannel sc = (SocketChannel) ch;
              if (conn != null) {
                if (conn.state == State.INSIDE && conn.nick != null) {
                  String LeftMsg = "LEFT " + conn.nick + "\n";
                  broadcast(LeftMsg, conn.room, key);
                }
                if (conn.nick != null) {
                  nicknameMap.remove(conn.nick);
                }
                conn.nick = null;
                conn.room = null;
                key.cancel();
                sc.close();
              }
            } else {
              key.cancel();
              if (ch != null)
                ch.close();
            }

          } catch (IOException ignore) {
          }
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
    if (bytesRead == -1) {
      switch (conn.state) {
        case INSIDE: {
          String LeftMsg = "LEFT " + conn.nick + "\n";
          broadcast(LeftMsg, conn.room, key);
          nicknameMap.remove(conn.nick);
          conn.nick = null;
          conn.room = null;
          return false;
        }
        case OUTSIDE: {
          nicknameMap.remove(conn.nick);
          conn.nick = null;
          return false;
        }
        default: {
          if (conn.nick != null) {
            nicknameMap.remove(conn.nick);
          }
          conn.nick = null;
          conn.room = null;
          return false;
        }
      }
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
      if (line.endsWith("\r"))
        line = line.substring(0, line.length() - 1);

      // Remove the processed line from the buffer
      conn.recv.delete(0, idx + 1);
      if (line.isEmpty())
        continue;
      // if the user is in init state and trying cmds that aren't /nick, ERROR
      // does the line start with '/'?

      if (line.startsWith("/")) {
        if (line.length() > 1 && line.charAt(1) == '/') {
          // it's just a message that starts with //
          String escapedMessage = line.substring(1);
          // Regular message: broadcast "nick: message"
          String RegularMsg = "MESSAGE " + conn.nick + " " + escapedMessage + "\n";
          if (conn.state == State.INSIDE) {
            broadcast(RegularMsg, conn.room, null);
          } else {
            send(conn, key, ERROR);
          }
        } else {
          String[] parts = line.split(" ", 3);
          String cmd = parts[0];
          switch (cmd) {
            case "/nick": {
              // used to pick a nickname or to change a nickname (the nickname can't already
              // be chosen by another user!)
              // change states from init to outside if they are in initialization, if they are
              // just changing the name and already outside or inside, the state doesn't
              // change
              if (parts.length < 2) {
                send(conn, key, ERROR);
                continue;
              } else {
                String new_nickname = parts[1];
                if (new_nickname != null && !new_nickname.isEmpty()) {
                  // check if nickname already exists
                  if (nicknameMap.containsKey(new_nickname)) {
                    send(conn, key, ERROR);
                    continue;
                  } else if(new_nickname.equals(conn.nick)){
                    send(conn, key, OK); 
                    continue;
                  }
                  else {
                    if (conn.nick == null && conn.state == State.INIT) { // if it's a nickname creation : state == INIT
                      conn.nick = new_nickname;
                      nicknameMap.put((new_nickname), conn);
                      send(conn, key, OK);
                      conn.state = State.OUTSIDE;
                      continue;

                    } else if (conn.nick != null && conn.state != State.INIT) {
                      nicknameMap.remove(conn.nick);
                      String NewNickMsg = "NEWNICK " + conn.nick + " " + new_nickname + "\n";
                      send(conn, key, OK);
                      if (conn.state == State.INSIDE) {
                        broadcast(NewNickMsg, conn.room, key);
                      }
                      conn.nick = new_nickname;
                      nicknameMap.put((new_nickname), conn);
                      continue;
                    }
                  }
                } else {
                  send(conn, key, ERROR);
                  continue;
                }
              }

            }
            case "/join": {
              // if the user is already in a room: LEFT , then JOINED, change state to inside
              // if they weren't before
              String JoinedMsg = "JOINED " + conn.nick + "\n";
              String LeftMsg = "LEFT " + conn.nick + "\n";
              if (parts.length < 2) {
                send(conn, key, ERROR);
                continue;

              }
              String new_room = parts[1];
              if (null != conn.state)
                switch (conn.state) {
                  case OUTSIDE:
                    conn.state = State.INSIDE;
                    send(conn, key, OK);
                    broadcast(JoinedMsg, new_room, key);
                    conn.room = new_room;
                    break;

                  case INIT:
                    send(conn, key, ERROR);
                    break;
                  case INSIDE:
                    send(conn, key, OK);
                    broadcast(LeftMsg, conn.room, key); // update new room!
                    broadcast(JoinedMsg, new_room, key);
                    conn.room = new_room;
                    break;

                  default:
                    send(conn, key, ERROR);
                    break;

                }
              continue;
            }
            case "/priv": {
              // /priv name msg

              if (parts.length < 3 || conn.state == State.INIT || conn.nick == null) {
                send(conn, key, ERROR);
                continue;
              }
              String target = parts[1];
              String msg = parts[2];
              Connection targetConn = nicknameMap.get(target);
              if (targetConn == null) {
                send(conn, key, ERROR);
                continue;
              }
              SelectionKey targetKey = targetConn.key;
              if (targetKey == null || !targetKey.isValid()) {
                send(conn, key, ERROR);
                continue;
              }
              String priv = "PRIVATE " + conn.nick + " " + msg + "\n";
              send(targetConn, targetKey, priv);
              send(conn, key, OK);
              continue;
            }
            case "/leave": {
              // change room to null
              // change state to outside
              String LeftMsg = "LEFT " + conn.nick + "\n";
              if (conn.state == State.INSIDE) {
                broadcast(LeftMsg, conn.room, key);
                conn.state = State.OUTSIDE;
                conn.room = null;
                send(conn, key, OK);
                continue;
              } else {
                send(conn, key, ERROR);
                continue;
              }
            }
            case "/bye": {
              String LeftMsg = "LEFT " + conn.nick + "\n";
              if (conn.state == State.INSIDE) {
                broadcast(LeftMsg, conn.room, key);
                // leave room
                conn.room = null;
              }
              nicknameMap.remove(conn.nick);
              conn.nick = null;
              send(conn, key, BYE);
              // close connection
              conn.closeAfterWrite = true;
              key.interestOps((key.interestOps() | SelectionKey.OP_WRITE) & ~SelectionKey.OP_READ);
              return true; // handleRead stops processing

            }
            default: {
              // erro!
              send(conn, key, ERROR);
            }

          }

        }
      } else {
        // Regular message: broadcast "nick: message"
        String RegularMsg = "MESSAGE " + conn.nick + " " + line + "\n";
        if (conn.state == State.INSIDE) {
          broadcast(RegularMsg, conn.room, null);
        } else {
          send(conn, key, ERROR);
        }
      }

    }return true;

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
    // if originKey is null, the loop runs normally using the global selector and k
    // == originKey is false, so it sends it to everyone
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
    if (conn.closeAfterWrite) {
      try {
        key.cancel();
      } catch (Exception ignore) {
      }
      try {
        sc.close();
      } catch (IOException ignore) {
      }
      conn.key = null;
      return;

    }
    // Nothing left to write: remove write interest
    if ((key.interestOps() & SelectionKey.OP_WRITE) != 0) {
      key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

  }

  // function to Unicast (send a msg to a unique client) -> to send OK and ERROR
  // regarding the client requests to the server
  private static void send(Connection conn, SelectionKey key, String message) {
    if (!message.endsWith("\n"))
      message += "\n"; // just in case
    byte[] data = message.getBytes(StandardCharsets.UTF_8); // converts strings to bytes
    ByteBuffer buffer = ByteBuffer.wrap(data); // puts them in a buffer
    conn.outQueue.add(buffer); // adds buffer to waiting queue of the connection
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE); // warns the selector that it wants to write
  }
}