import java.io.*;
import java.net.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

public class ChatClient {

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto ChatClient, devem
    // ser colocadas aqui
    private Socket socket;
    private BufferedReader input; // buffered reader used to readline as chat server is line based
    private PrintWriter output; // PrintWriter used as it supports println.
    private Thread listenThread;
    private boolean active;
    private String server;
    private int port;

    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message);
    }

    // Construtor
    public ChatClient(String server, int port) throws IOException {

        // Inicialização da interface gráfica --- * NÃO MODIFICAR *
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    newMessage(chatBox.getText());
                } catch (IOException ex) {
                } finally {
                    chatBox.setText("");
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocusInWindow();
            }
        });
        // --- Fim da inicialização da interface gráfica

        // Se for necessário adicionar código de inicialização ao
        // construtor, deve ser colocado aqui
        this.active = true;
        this.server = server;
        this.port = port;
    }

    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void newMessage(String message) throws IOException {
        // PREENCHER AQUI com código que envia a mensagem ao servidor
        // handle empty enters
        if (message == null || message.isEmpty()) {
            return;
        }

        // escape rule check.. if command or not (if not command but starts with / send
        // it with an extra / to be processed as text)
        boolean isCmd = message.startsWith("/nick") || message.startsWith("/join")
                || message.startsWith("/leave") || message.startsWith("/bye") || message.startsWith("/priv");

        if (message.charAt(0) == '/' && !isCmd) {
            message = "/" + message;
        }

        output.println(message); // send message to server
        output.flush(); // pushes buffer into sockets output stream
    }

    private void processServerMessagesPrettyPrint(String line) {
        if (line == null) {
            return;
        }

        String[] part = line.split(" ", 3);
        String action = part[0];

        switch (action) {
            case "MESSAGE":
                printMessage(part[1] + ": " + part[2] + "\n");
                break;
            case "PRIVATE":
                printMessage(part[1] + " (mensagem privada): " + part[2] + "\n");
                break;
            case "NEWNICK":
                printMessage(part[1] + " mudou de nome para " + part[2] + "\n");
                break;
            case "JOINED":
                printMessage(part[1] + " entrou na sala\n");
                break;
            case "LEFT":
                printMessage(part[1] + " saiu da sala\n");
                break;
            case "BYE":
                // remove socket as user has left
                active = false;
                try {
                    // close socket them dispose of GUI then close app entirely (this step-by-step
                    // close ensures safe closure of program and threads)
                    socket.close();
                    SwingUtilities.invokeLater(() -> {
                        frame.dispose();
                        System.exit(0);
                    });

                } catch (Exception ignore) {
                }
                break;
            default:
                printMessage(line + "\n");
                break;
        }
    }

    // Método principal do objecto
    public void run() throws IOException {
        // PREENCHER AQUI
        // connect socket to server
        socket = new Socket(server, port);
        input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true); // true is auto flush

        // creating reader thread
        listenThread = new Thread(() -> {
            try {
                String line;
                while (active && (line = input.readLine()) != null) {
                    processServerMessagesPrettyPrint(line);
                }
            } catch (IOException e) {
                // connection closed in case of IOExceptions
            } finally {
                active = false;
                try {
                    socket.close();
                    SwingUtilities.invokeLater(() -> {
                        frame.dispose();
                        System.exit(0);
                    });
                } catch (Exception ignore) {
                }
            }
        });

        listenThread.start();
    }

    // Instancia o ChatClient e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }

}