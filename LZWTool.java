import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LZWTool {

    // --- Global constants ---
    private int A;      // Alphabet size
    private int CLEAR;  // Reset code
    private int BASE;   // First code available for new entries
    private static final int MAX_CODES = 1 << 16;

    // --- Header bit widths ---
    private static final int BITS_PER_POLICY = 2;
    private static final int BITS_PER_WIDTH = 5;
    private static final int BITS_PER_A_SIZE = 16;
    private static final int BITS_PER_SYMBOL = 8;

    // --- Configuration holder ---
    private static final class Config {
        String mode;
        int minW = 9, maxW = 16;
        Policy policy = Policy.FREEZE;
        String alphabetPath;
        List<String> alphabet;
    }

    // --- Policy enum ---
    private enum Policy {
        FREEZE, RESET, LRU, LFU;

        static Policy fromString(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "freeze": return FREEZE;
                case "reset":  return RESET;
                case "lru":    return LRU;
                case "lfu":    return LFU;
                default: throw new IllegalArgumentException("Unknown policy: " + s);
            }
        }

        int code() {
            switch (this) {
                case FREEZE: return 0;
                case RESET:  return 1;
                case LRU:    return 2;
                case LFU:    return 3;
            }
            return 0;
        }

        static Policy fromCode(int c) {
            switch (c) {
                case 0: return FREEZE;
                case 1: return RESET;
                case 2: return LRU;
                case 3: return LFU;
                default: throw new IllegalArgumentException("Bad policy code: " + c);
            }
        }
    }

    // --- Entry point ---
    public static void main(String[] args) {
        try {
            new LZWTool().run(args);
        } catch (Throwable t) {
            System.err.println("Fatal error: " + t.getMessage());
            System.exit(2);
        }
    }

    private void run(String[] args) {
        Config cfg = parseArgs(args);
        if ("compress".equals(cfg.mode)) {
            validateConfig(cfg);
            cfg.alphabet = readAlphabet(cfg.alphabetPath);
            A = cfg.alphabet.size();
            CLEAR = A;
            BASE = A + 1;
            compress(cfg);
        } else if ("expand".equals(cfg.mode)) {
            expand();
        } else {
            throw new IllegalArgumentException("Mode must be 'compress' or 'expand'.");
        }
    }

    // --- Read custom alphabet file ---
    private List<String> readAlphabet(String path) {
        Set<String> symbols = new LinkedHashSet<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null)
                if (!line.isEmpty())
                    symbols.add(line.substring(0, 1));
        } catch (IOException e) {
            throw new RuntimeException("Error reading alphabet: " + path, e);
        }

        // Always ensure ASCII coverage (needed for generic inputs)
        for (int i = 0; i < 256; i++) {
            String s = new String(new byte[]{(byte) i}, StandardCharsets.ISO_8859_1);
            symbols.add(s);
        }
        return new ArrayList<>(symbols);
    }

    // --- Header writer/reader ---
    private void writeHeader(Config cfg) {
        BinaryStdOut.write(cfg.policy.code(), BITS_PER_POLICY);
        BinaryStdOut.write(cfg.minW, BITS_PER_WIDTH);
        BinaryStdOut.write(cfg.maxW, BITS_PER_WIDTH);
        BinaryStdOut.write(cfg.alphabet.size(), BITS_PER_A_SIZE);
        for (String s : cfg.alphabet)
            BinaryStdOut.write(s.getBytes(StandardCharsets.ISO_8859_1)[0] & 0xFF, BITS_PER_SYMBOL);
    }

    private Config readHeader() {
        Config h = new Config();
        h.policy = Policy.fromCode(BinaryStdIn.readInt(BITS_PER_POLICY));
        h.minW = BinaryStdIn.readInt(BITS_PER_WIDTH);
        h.maxW = BinaryStdIn.readInt(BITS_PER_WIDTH);
        int size = BinaryStdIn.readInt(BITS_PER_A_SIZE);

        h.alphabet = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int val = BinaryStdIn.readInt(BITS_PER_SYMBOL);
            h.alphabet.add(new String(new byte[]{(byte) val}, StandardCharsets.ISO_8859_1));
        }

        A = size;
        CLEAR = A;
        BASE = A + 1;
        return h;
    }

    // --- Simple argument parser ---
    private Config parseArgs(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":     c.mode = args[++i]; break;
                case "--minW":     c.minW = Integer.parseInt(args[++i]); break;
                case "--maxW":     c.maxW = Integer.parseInt(args[++i]); break;
                case "--policy":   c.policy = Policy.fromString(args[++i]); break;
                case "--alphabet": c.alphabetPath = args[++i]; break;
                default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }
        return c;
    }

    private void validateConfig(Config c) {
        if (c.minW < 3 || c.maxW > 16 || c.minW > c.maxW)
            throw new IllegalArgumentException("Invalid width range.");
        if (c.alphabetPath == null)
            throw new IllegalArgumentException("Alphabet file required.");
    }

    // --- Minimal encoder dictionary wrapper ---
    private static class EncoderCodebook {
        private final TSTmod<Integer> tst = new TSTmod<>();
        private final Map<String, Integer> map = new HashMap<>();
        void put(String s, int code) { tst.put(new StringBuilder(s), code); map.put(s, code); }
        Integer get(String s) { return map.get(s); }
    }

    // --- Compression ---
    private void compress(Config cfg) {
        int minBitsForAlphabet = (int) Math.ceil(Math.log(BASE) / Math.log(2));
        int W = Math.max(cfg.minW, minBitsForAlphabet);
        int L = 1 << W;
        int nextCode = BASE;

        EncoderCodebook cb = new EncoderCodebook();
        for (int i = 0; i < A; i++) cb.put(cfg.alphabet.get(i), i);
        writeHeader(cfg);

        int first;
        try { first = BinaryStdIn.readInt(BITS_PER_SYMBOL); }
        catch (NoSuchElementException e) { BinaryStdOut.close(); return; }

        String p = new String(new byte[]{(byte) first}, StandardCharsets.ISO_8859_1);

        try {
            while (true) {
                int b;
                try { b = BinaryStdIn.readInt(BITS_PER_SYMBOL); }
                catch (NoSuchElementException e) { break; }

                String c = new String(new byte[]{(byte) b}, StandardCharsets.ISO_8859_1);
                String pc = p + c;

                if (cb.get(pc) != null) {
                    p = pc;
                } else {
                    Integer pCode = cb.get(p);
                    if (pCode != null) BinaryStdOut.write(pCode, W);

                    // --- key fix: check width BEFORE inserting new entry ---
                    if (nextCode >= L && W < cfg.maxW) {
                        W++;
                        L = 1 << W;
                    }

                    if (nextCode < L) {
                        cb.put(pc, nextCode++);
                    } else if (W >= cfg.maxW && cfg.policy == Policy.RESET) {
                        BinaryStdOut.write(CLEAR, W);
                        W = cfg.minW;
                        L = 1 << W;
                        nextCode = BASE;
                        cb = new EncoderCodebook();
                        for (int i = 0; i < A; i++) cb.put(cfg.alphabet.get(i), i);
                    }

                    p = c;
                }
            }

            // Write last phrase
            Integer pCode = cb.get(p);
            if (pCode != null) BinaryStdOut.write(pCode, W);
            BinaryStdOut.flush();
        } finally {
            BinaryStdOut.close();
        }
    }

    // --- Expansion ---
    private void expand() {
        Config cfg = readHeader();
        int minBitsForAlphabet = (int) Math.ceil(Math.log(BASE) / Math.log(2));
        int W = Math.max(cfg.minW, minBitsForAlphabet);
        int L = 1 << W;
        int nextCode = BASE;
        Policy policy = cfg.policy;

        List<String> dict = new ArrayList<>(Collections.nCopies(MAX_CODES, null));
        for (int i = 0; i < A; i++) dict.set(i, cfg.alphabet.get(i));

        int prevCode;
        try { prevCode = BinaryStdIn.readInt(W); }
        catch (NoSuchElementException e) { return; }

        String prevStr = dict.get(prevCode);
        for (char ch : prevStr.toCharArray()) BinaryStdOut.write((int) ch, BITS_PER_SYMBOL);

        try {
            while (!BinaryStdIn.isEmpty()) {
                if (nextCode == L && W < cfg.maxW) { W++; L = 1 << W; }

                int curCode = BinaryStdIn.readInt(W);
                if (curCode == CLEAR && policy == Policy.RESET) {
                    W = cfg.minW; L = 1 << W; nextCode = BASE;
                    dict = new ArrayList<>(Collections.nCopies(MAX_CODES, null));
                    for (int i = 0; i < A; i++) dict.set(i, cfg.alphabet.get(i));
                    if (BinaryStdIn.isEmpty()) break;
                    prevCode = BinaryStdIn.readInt(W);
                    prevStr = dict.get(prevCode);
                    for (char ch : prevStr.toCharArray())
                        BinaryStdOut.write((int) ch, BITS_PER_SYMBOL);
                    continue;
                }

                String curStr;
                if (curCode < nextCode) {
                    curStr = dict.get(curCode);
                } else if (curCode == nextCode) {
                    curStr = prevStr + prevStr.charAt(0);
                } else break;

                for (char ch : curStr.toCharArray())
                    BinaryStdOut.write((int) ch, BITS_PER_SYMBOL);

                if (nextCode < L)
                    dict.set(nextCode++, prevStr + curStr.charAt(0));

                prevStr = curStr;
            }
        } catch (NoSuchElementException ignore) {
        } finally {
            BinaryStdOut.close();
        }
    }
}
