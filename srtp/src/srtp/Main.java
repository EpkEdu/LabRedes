package srtp;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Ponto de entrada do SRTP — Simple Reliable Transport Protocol.
 *
 * Uso:
 *   Receiver: java -jar srtp.jar --listen --port <P> [--output <arquivo>] [--window <N>]
 *   Sender:   java -jar srtp.jar --host <host> --port <P> --file <arquivo> [--window <N>] [--mode <saw|gbn|sr>]
 */
public class Main {

    public static void main(String[] args) {
        // Garante saída UTF-8 independente do sistema operacional
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        CLIArgs cli = parseArgs(args);
        if (cli == null) {
            printUsage();
            System.exit(1);
        }

        try {
            if (cli.listen) {
                // ─── Modo Receiver ────────────────────────────────────────────
                File out = cli.outputFile != null
                        ? new File(cli.outputFile)
                        : new File("received_output.bin");

                System.out.println("=== SRTP Receiver | Modo=" + cli.mode.toUpperCase() +
                        " | Porta=" + cli.port +
                        " | Janela=" + cli.window +
                        " | Output=" + out.getName() + " ===");

                switch (cli.mode) {
                    case "saw" -> new Receiver(cli.port, out, cli.window).run();
                    // Parte 2: GBN e SR serão adicionados aqui
                    default -> {
                        System.err.println("Modo '" + cli.mode + "' ainda não implementado nesta parte.");
                        System.exit(1);
                    }
                }

            } else {
                // ─── Modo Sender ──────────────────────────────────────────────
                if (cli.host == null || cli.file == null) {
                    System.err.println("Erro: --host e --file são obrigatórios no modo sender.");
                    printUsage();
                    System.exit(1);
                }

                File f = new File(cli.file);
                if (!f.exists() || !f.isFile()) {
                    System.err.println("Erro: arquivo não encontrado: " + cli.file);
                    System.exit(1);
                }

                System.out.println("=== SRTP Sender | Modo=" + cli.mode.toUpperCase() +
                        " | Host=" + cli.host +
                        " | Porta=" + cli.port +
                        " | Arquivo=" + cli.file +
                        " | Janela=" + cli.window + " ===");

                switch (cli.mode) {
                    case "saw" -> new Sender(cli.host, cli.port, f, cli.window).run();
                    // Parte 2: GBN e SR serão adicionados aqui
                    default -> {
                        System.err.println("Modo '" + cli.mode + "' ainda não implementado nesta parte.");
                        System.exit(1);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Erro fatal: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ─── Parse de argumentos CLI ──────────────────────────────────────────────

    static CLIArgs parseArgs(String[] args) {
        CLIArgs cli = new CLIArgs();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--listen"  -> cli.listen = true;
                case "--port"    -> {
                    if (i + 1 >= args.length) return null;
                    try { cli.port = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) { return null; }
                }
                case "--host"    -> { if (i + 1 >= args.length) return null; cli.host = args[++i]; }
                case "--file"    -> { if (i + 1 >= args.length) return null; cli.file = args[++i]; }
                case "--output"  -> { if (i + 1 >= args.length) return null; cli.outputFile = args[++i]; }
                case "--window"  -> {
                    if (i + 1 >= args.length) return null;
                    try {
                        cli.window = Integer.parseInt(args[++i]);
                        if (cli.window < 1 || cli.window > 255) {
                            System.err.println("Janela deve ser entre 1 e 255.");
                            return null;
                        }
                    } catch (NumberFormatException e) { return null; }
                }
                case "--mode"    -> {
                    if (i + 1 >= args.length) return null;
                    cli.mode = args[++i].toLowerCase();
                    if (!cli.mode.equals("saw") && !cli.mode.equals("gbn") && !cli.mode.equals("sr")) {
                        System.err.println("Modo inválido: " + cli.mode + ". Use: saw, gbn, sr");
                        return null;
                    }
                }
                case "--help", "-h" -> { return null; }
                default -> {
                    System.err.println("Argumento desconhecido: " + args[i]);
                    return null;
                }
            }
        }

        if (cli.port <= 0 || cli.port > 65534) {
            System.err.println("Porta inválida: " + cli.port +
                    " (deve estar entre 1 e 65534 para permitir P+1)");
            return null;
        }

        return cli;
    }

    static void printUsage() {
        System.out.println("""
            ╔══════════════════════════════════════════════════════════════╗
            ║         SRTP — Simple Reliable Transport Protocol           ║
            ╚══════════════════════════════════════════════════════════════╝

            USO:
              Receiver: java -jar srtp.jar --listen --port <P> [opções]
              Sender:   java -jar srtp.jar --host <host> --port <P> --file <arquivo> [opções]

            ARGUMENTOS OBRIGATÓRIOS:
              --port <P>        Porta base (1–65534)
                                  Receiver escuta em P
                                  Sender envia para P; recebe ACKs em P+1
              --host <host>     IP/hostname do receiver     [apenas sender]
              --file <arquivo>  Arquivo a transferir        [apenas sender]

            ARGUMENTOS OPCIONAIS:
              --listen          Ativa modo receiver
              --output <arq>    Arquivo de saída            [receiver; padrão: received_output.bin]
              --window <N>      Tamanho da janela 1–255     [padrão: 1]
              --mode <modo>     saw | gbn | sr              [padrão: saw]

            EXEMPLOS:
              # Receiver na porta 6000
              java -jar srtp.jar --listen --port 6000 --output recebido.bin

              # Sender → 192.168.1.10:6000 (escuta ACKs em :6001)
              java -jar srtp.jar --host 192.168.1.10 --port 6000 --file dados.bin

              # Com modo e janela explícitos
              java -jar srtp.jar --host 192.168.1.10 --port 6000 --file dados.bin --mode saw --window 1
            """);
    }

    // ─── Estrutura de argumentos ──────────────────────────────────────────────

    static class CLIArgs {
        boolean listen     = false;
        int     port       = 0;
        String  host       = null;
        String  file       = null;
        String  outputFile = null;
        int     window     = 1;
        String  mode       = "saw";
    }
}
