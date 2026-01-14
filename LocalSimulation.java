import java.io.*;
import java.util.concurrent.TimeUnit;

public class LocalSimulation {

    public static void main(String[] args) {
        try {
            System.out.println("Kompilacja BattleshipGame.java");
            Process compile = new ProcessBuilder("javac", "BattleshipGame.java").inheritIO().start();
            compile.waitFor();
            if (compile.exitValue() != 0) {
                System.err.println("Blad kompilacji");
                return;
            }
            createMapFile("map_server.txt", generateMapContent());
            createMapFile("map_client.txt", generateMapContent());

            System.out.println("Uruchamianie serwera (Port 9999)");
            ProcessBuilder serverPb = new ProcessBuilder(
                    "java", "BattleshipGame",
                    "-mode", "server",
                    "-port", "9999",
                    "-map", "map_server.txt"
            );
            serverPb.inheritIO();
            Process serverProcess = serverPb.start();

            TimeUnit.SECONDS.sleep(1);

            ProcessBuilder clientPb = new ProcessBuilder(
                    "java", "BattleshipGame",
                    "-mode", "client",
                    "-host", "localhost",
                    "-port", "9999",
                    "-map", "map_client.txt"
            );
            clientPb.inheritIO();
            Process clientProcess = clientPb.start();

            int clientExit = clientProcess.waitFor();
            serverProcess.destroy();

            System.out.println("Koniec");

            new File("map_server.txt").delete();
            new File("map_client.txt").delete();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void createMapFile(String filename, String content) throws IOException {
        try (PrintWriter out = new PrintWriter(filename)) {
            out.print(content);
        }
    }

    private static String generateMapContent() {//sample
        return
                ".........." + System.lineSeparator() +
                        ".####....." + System.lineSeparator() +
                        ".........." + System.lineSeparator() +
                        "...###...." + System.lineSeparator() +
                        ".........." + System.lineSeparator() +
                        ".....##..." + System.lineSeparator() +
                        ".........." + System.lineSeparator() +
                        ".##......." + System.lineSeparator() +
                        ".........." + System.lineSeparator() +
                        "......#...";
    }
}