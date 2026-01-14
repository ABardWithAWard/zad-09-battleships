import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class BattleshipGame {
    private static boolean isHuman = false;

    private static final int GRID_SIZE = 10;
    private static final int TIMEOUT_MS = 100000; // if too short you cant play as connection is getting severed after timeout
    private static final int MAX_RETRIES = 3;

    private static char[][] myMap = new char[GRID_SIZE][GRID_SIZE];
    private static char[][] myHistoryMap = new char[GRID_SIZE][GRID_SIZE];
    private static char[][] enemyMap = new char[GRID_SIZE][GRID_SIZE];

    private static List<Set<Point>> myShips = new ArrayList<>();
    private static Set<Point> shotsFiredByMe = new HashSet<>();

    private static String lastSentMessage = null;
    private static boolean isServer = false;
    private static boolean gameOver = false;

    static class Point {
        int r, c;
        Point(int r, int c) { this.r = r; this.c = c; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return r == point.r && c == point.c;
        }

        @Override
        public int hashCode() { return Objects.hash(r, c); }

        @Override
        public String toString() {
            return String.format("%c%d", (char)('A' + c), r + 1);
        }
    }

    public static void main(String[] args) {
        String mode = null;
        int port = 0;
        String mapFile = null;
        String host = null;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-mode": mode = args[++i]; break;
                    case "-port": port = Integer.parseInt(args[++i]); break;
                    case "-map": mapFile = args[++i]; break;
                    case "-host": host = args[++i]; break;
                    case "-human": isHuman = true; ++i; break;
                }
            }
        } catch (Exception e) {
            System.out.println("Blad parametrow uruchomieniowych.");
            return;
        }

        if (mode == null || mapFile == null) {
            System.out.println("Wymagane parametry: -mode oraz -map");
            return;
        }

        try {
            loadMap(mapFile);
        } catch (IOException e) {
            System.out.println("Nie udalo sie wczytac mapy: " + e.getMessage());
            return;
        }
        initEmptyMaps();

        System.out.println("Moja mapa:");
        printBoard(myMap);

        try {
            if ("server".equals(mode)) {
                isServer = true;
                runServer(port);
            } else {
                runClient(host, port);
            }
        } catch (IOException e) {
            System.out.println("Błąd sieci: " + e.getMessage());
        }
    }

    private static void runServer(int port) throws IOException {
        System.out.println("Oczekiwanie na polaczenie na porcie " + port + "...");
        try (ServerSocket serverSocket = new ServerSocket(port);
             Socket socket = serverSocket.accept()) {

            socket.setSoTimeout(TIMEOUT_MS);
            System.out.println("Klient połączony.");
            handleGameLoop(socket);
        }
    }

    private static void runClient(String host, int port) throws IOException {
        System.out.println("Laczenie z " + host + ":" + port + "...");
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(TIMEOUT_MS);
            System.out.println("Polaczono.");

            Point firstShot = generateShot();
            sendMessage(socket, "start;" + firstShot);

            handleGameLoop(socket);
        }
    }

    private static void handleGameLoop(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

        while (!gameOver) {
            String receivedLine = receiveWithRetry(reader, writer);
            if (receivedLine == null) break;

            System.out.println("Otrzymano: " + receivedLine);

            String[] parts = receivedLine.split(";");
            String command = parts[0];
            String coordsStr = parts.length > 1 ? parts[1] : null;

            if (!command.equals("start")) {
                processCommandResult(command);
                if (gameOver) break;
            }

            if (coordsStr != null) {
                Point enemyShot = parseCoordinates(coordsStr);
                String result = processEnemyShot(enemyShot);

                if (result.equals("ostatni zatopiony")) {
                    sendMessage(socket, result);
                    endGame(false);
                    break;
                } else {
                    Point myNextShot = generateShot();
                    sendMessage(socket, result + ";" + myNextShot);
                }
            }
        }
    }

    private static String receiveWithRetry(BufferedReader reader, PrintWriter writer) throws IOException {
        int retries = 0;
        while (retries < MAX_RETRIES) {
            try {
                String line = reader.readLine();
                if (line == null) throw new IOException("Polaczenie zerwane");
                if (!line.contains(";") && !line.equals("ostatni zatopiony")) {
                    throw new IOException("Niezrozumiala komenda");
                }
                return line;
            } catch (IOException e) {
                retries++;
                if (retries >= MAX_RETRIES) {
                    System.out.println("Blad komunikacji");
                    System.exit(0);
                    return null;
                }
                if (lastSentMessage != null) {
                    System.out.println("Ponawianie wysyłania (" + retries + "/" + MAX_RETRIES + ")...");
                    writer.print(lastSentMessage);
                    writer.flush();
                } else {

                }
            }
        }
        return null;
    }

    private static void sendMessage(Socket socket, String msg) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        String fullMsg = msg.endsWith("\n") ? msg : msg + "\n";
        lastSentMessage = fullMsg;
        System.out.print("Wysylano: " + fullMsg);
        writer.print(fullMsg);
        writer.flush();
    }

    private static void processCommandResult(String command) {
        if (lastSentMessage == null) return;

        String[] parts = lastSentMessage.trim().split(";");
        if (parts.length < 2) return;

        Point lastTarget = parseCoordinates(parts[1]);

        switch (command) {
            case "pudlo":
                enemyMap[lastTarget.r][lastTarget.c] = '.';
                break;
            case "trafiony":
                enemyMap[lastTarget.r][lastTarget.c] = '#';
                break;
            case "trafiony zatopiony":
                enemyMap[lastTarget.r][lastTarget.c] = '#';
                markSunkShipOnEnemyMap(lastTarget);
                break;
            case "ostatni zatopiony":
                enemyMap[lastTarget.r][lastTarget.c] = '#';
                markSunkShipOnEnemyMap(lastTarget);
                endGame(true);
                break;
        }
    }
    private static String processEnemyShot(Point p) {
        char status = myMap[p.r][p.c];
        if (myHistoryMap[p.r][p.c] == '@' || myHistoryMap[p.r][p.c] == '~') {
            if (status == '#') {
                if (isShipSunk(p)) return "trafiony zatopiony";
                return "trafiony";
            }
            return "pudlo";
        }

        if (status == '.') {
            myHistoryMap[p.r][p.c] = '~';
            return "pudło";
        } else if (status == '#') {
            myHistoryMap[p.r][p.c] = '@';
            for (Set<Point> ship : myShips) {
                if (ship.contains(p)) {
                    ship.remove(p);
                    if (ship.isEmpty()) {
                        myShips.remove(ship);
                        if (myShips.isEmpty()) {
                            return "ostatni zatopiony";
                        }
                        return "trafiony zatopiony";
                    }
                    break;
                }
            }
            return "trafiony";
        }
        return "pudlo";
    }

    private static boolean isShipSunk(Point p) {
        for (Set<Point> ship : myShips) {
            if (ship.contains(p)) return false;
        }
        return true;
    }

    private static Point generateShot() {
        if (isHuman) {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                try {
                    System.out.print("Twoja kolej podaj wspolrzedne (np. A5): ");
                    String input = scanner.nextLine().trim().toUpperCase();
                    if (input.isEmpty()) continue;
                    Point p = parseCoordinates(input);
                    if (isValid(p.r, p.c)) return p;
                } catch (Exception e) {
                    System.out.println("Bledny format uzyj litery A-J i liczby 1-10.");
                }
            }
        } else { //if playing vs bot just randomize, but dont repeat existing shots
            Random rand = new Random();
            Point p;
            do {
                p = new Point(rand.nextInt(GRID_SIZE), rand.nextInt(GRID_SIZE));
            } while (shotsFiredByMe.contains(p));
            shotsFiredByMe.add(p);
            return p;
        }
    }

    private static void markSunkShipOnEnemyMap(Point lastHit) {
        Set<Point> shipParts = new HashSet<>();
        Queue<Point> queue = new LinkedList<>();
        queue.add(lastHit);
        shipParts.add(lastHit);

        while(!queue.isEmpty()){
            Point curr = queue.poll();
            int[][] dirs = {{0,1}, {0,-1}, {1,0}, {-1,0}};
            for(int[] d : dirs){
                int nr = curr.r + d[0];
                int nc = curr.c + d[1];
                if(isValid(nr, nc) && enemyMap[nr][nc] == '#' && !shipParts.contains(new Point(nr,nc))){
                    Point next = new Point(nr, nc);
                    shipParts.add(next);
                    queue.add(next);
                }
            }
        }

        for(Point part : shipParts){
            for(int dr = -1; dr <= 1; dr++){
                for(int dc = -1; dc <= 1; dc++){
                    int nr = part.r + dr;
                    int nc = part.c + dc;
                    if(isValid(nr, nc) && enemyMap[nr][nc] == 0){
                        enemyMap[nr][nc] = '.';
                    }
                }
            }
        }
    }


    private static void loadMap(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path));
        boolean[][] visited = new boolean[GRID_SIZE][GRID_SIZE];

        for (int r = 0; r < GRID_SIZE; r++) {
            String line = (r < lines.size()) ? lines.get(r) : "..........";
            for (int c = 0; c < GRID_SIZE; c++) {
                char ch = (c < line.length()) ? line.charAt(c) : '.';
                myMap[r][c] = ch;
            }
        }
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                if (myMap[r][c] == '#' && !visited[r][c]) {
                    Set<Point> ship = new HashSet<>();
                    findShipParts(r, c, visited, ship);
                    myShips.add(ship);
                }
            }
        }
    }

    private static void findShipParts(int r, int c, boolean[][] visited, Set<Point> ship) {
        if (!isValid(r, c) || visited[r][c] || myMap[r][c] != '#') return;
        visited[r][c] = true;
        ship.add(new Point(r, c));
        findShipParts(r + 1, c, visited, ship);
        findShipParts(r - 1, c, visited, ship);
        findShipParts(r, c + 1, visited, ship);
        findShipParts(r, c - 1, visited, ship);
    }

    private static void initEmptyMaps() {
        for(int i=0; i<GRID_SIZE; i++) {
            Arrays.fill(myHistoryMap[i], '.');
        }
    }

    private static boolean isValid(int r, int c) {
        return r >= 0 && r < GRID_SIZE && c >= 0 && c < GRID_SIZE;
    }

    private static Point parseCoordinates(String s) {
        s = s.trim().toUpperCase();
        char colChar = s.charAt(0);
        int row = Integer.parseInt(s.substring(1)) - 1;
        int col = colChar - 'A';
        return new Point(row, col);
    }

    private static void endGame(boolean win) {
        gameOver = true;
        System.out.println(win ? "Wygrana" : "Przegrana");

        System.out.println("Mapa przeciwnika:");
        if (win) {
            printEnemyMap(false);
        } else {
            printEnemyMap(true);
        }

        System.out.println();
        System.out.println("Twoja mapa:");
        printMyResultMap();
    }

    private static void printBoard(char[][] map) {
        for (char[] row : map) {
            System.out.println(new String(row));
        }
    }

    private static void printEnemyMap(boolean maskUnknown) {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                char ch = enemyMap[r][c];
                if (ch == 0) {
                    System.out.print(maskUnknown ? "?" : ".");
                } else {
                    System.out.print(ch);
                }
            }
            System.out.println();
        }
    }

    private static void printMyResultMap() {
        for (int r = 0; r < GRID_SIZE; r++) {
            for (int c = 0; c < GRID_SIZE; c++) {
                if (myHistoryMap[r][c] == '@') {
                    System.out.print("@");
                } else if (myHistoryMap[r][c] == '~') {
                    System.out.print("~");
                } else {
                    System.out.print(myMap[r][c]);
                }
            }
            System.out.println();
        }
    }
}