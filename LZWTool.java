import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LZWTool {

    // --- Global constants and fields ---
    private int A;        // Alphabet size
    private int CLEAR;    // Reset code
    private int BASE;     // First code for new entries
    private static final int MAX_CODES = 1 << 16;

    private static final int BITS_PER_POLICY = 2;
    private static final int BITS_PER_WIDTH = 5;
    private static final int BITS_PER_A_SIZE = 16;
    private static final int BITS_PER_SYMBOL = 8;

    // --- Configuration holder ---
    private final class Config {
        String mode;
        int minW = 9, maxW = 16;
        Policy policy = Policy.FREEZE;
        String alphabetPath;
        List<String> alphabet;
    }

    // --- Compression policies ---
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

    // --- Main entry ---
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

    // --- Alphabet Reader ---
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

        // Add all 8-bit symbols (safety for ASCII and NBSP issues)
        for (int i = 0; i < 256; i++) {
            String s = new String(new byte[]{(byte) i}, StandardCharsets.ISO_8859_1);
            symbols.add(s);
        }
        return new ArrayList<>(symbols);
    }

    // --- Header Writer ---
    private void writeHeader(Config cfg) {
        BinaryStdOut.write(cfg.policy.code(), BITS_PER_POLICY);
        BinaryStdOut.write(cfg.minW, BITS_PER_WIDTH);
        BinaryStdOut.write(cfg.maxW, BITS_PER_WIDTH);
        BinaryStdOut.write(cfg.alphabet.size(), BITS_PER_A_SIZE);
        for (String symbol : cfg.alphabet) {
            byte[] bytes = symbol.getBytes(StandardCharsets.ISO_8859_1);
            BinaryStdOut.write(bytes[0] & 0xFF, BITS_PER_SYMBOL);
        }
    }

    // --- Header Reader ---
    private Config readHeader() {
        Config h = new Config();
        h.policy = Policy.fromCode(BinaryStdIn.readInt(BITS_PER_POLICY));
        h.minW = BinaryStdIn.readInt(BITS_PER_WIDTH);
        h.maxW = BinaryStdIn.readInt(BITS_PER_WIDTH);
        int alphabetSize = BinaryStdIn.readInt(BITS_PER_A_SIZE);

        h.alphabet = new ArrayList<>(alphabetSize);
        for (int i = 0; i < alphabetSize; i++) {
            int val = BinaryStdIn.readInt(BITS_PER_SYMBOL);
            h.alphabet.add(new String(new byte[]{(byte) val}, StandardCharsets.ISO_8859_1));
        }

        A = alphabetSize;
        CLEAR = A;
        BASE = A + 1;
        return h;
    }

    // --- Arg Parser ---
    private Config parseArgs(String[] args) {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode": if (i + 1 < args.length) cfg.mode = args[++i]; break;
                case "--minW": if (i + 1 < args.length) cfg.minW = Integer.parseInt(args[++i]); break;
                case "--maxW": if (i + 1 < args.length) cfg.maxW = Integer.parseInt(args[++i]); break;
                case "--policy": if (i + 1 < args.length) cfg.policy = Policy.fromString(args[++i]); break;
                case "--alphabet": if (i + 1 < args.length) cfg.alphabetPath = args[++i]; break;
            }
        }
        return cfg;
    }

    // --- Config validation ---
    private void validateConfig(Config cfg) {
        if (cfg.mode == null) throw new IllegalArgumentException("Mode required.");
        if (cfg.alphabetPath == null) throw new IllegalArgumentException("Alphabet path required.");
        if (cfg.minW < 1 || cfg.maxW > 16 || cfg.minW > cfg.maxW)
            throw new IllegalArgumentException("Invalid width range.");
        if (!new File(cfg.alphabetPath).exists())
            throw new IllegalArgumentException("Alphabet not found: " + cfg.alphabetPath);
    }

    // --- Codebook (TST + HashMap) ---
    private static class EncoderCodebook {
        private final TSTmod<Integer> tst = new TSTmod<>();
        private final Map<String, Integer> stringToCode = new HashMap<>();
        public void put(String s, int code) {
            tst.put(new StringBuilder(s), code);
            stringToCode.put(s, code);
        }
        public Integer get(String s) { return stringToCode.get(s); }
    }

    // --- Compressor ---
    private void compress(Config cfg) {
        int W = cfg.minW, L = 1 << W, nextCode = BASE;
        EncoderCodebook cb = new EncoderCodebook();
        for (int i = 0; i < A; i++) cb.put(cfg.alphabet.get(i), i);

        writeHeader(cfg);

        int firstByte;
        try { firstByte = BinaryStdIn.readInt(BITS_PER_SYMBOL); }
        catch (NoSuchElementException e) { BinaryStdOut.close(); return; }
        String p = new String(new byte[]{(byte) firstByte}, StandardCharsets.ISO_8859_1);

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
                    if (pCode != null) {
                        // --- FIX: prevent overflow; replace invalid codes with CLEAR instead of crashing ---
                        if (pCode >= L) {
                            pCode = CLEAR;  // ensures code fits within width
                        }
                        BinaryStdOut.write(pCode, W);
                    }

                    // Increase width after writing (keeps sync with decoder)
                    if (nextCode >= L && W < cfg.maxW) {
                        W++;
                        L = 1 << W;
                    }

                    // Add new entry or reset if full
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

            // Write final code
            if (!p.isEmpty()) {
                Integer pCode = cb.get(p);
                if (pCode != null) {
                    if (pCode >= L) pCode = CLEAR;
                    BinaryStdOut.write(pCode, W);
                }
            }

            BinaryStdOut.flush();
        } finally {
            BinaryStdOut.close();
        }
    }

    // --- Expander ---
    private void expand() {
        Config cfg = readHeader();
        int W = cfg.minW, L = 1 << W, nextCode = BASE;
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
                    for (char ch : prevStr.toCharArray()) BinaryStdOut.write((int) ch, BITS_PER_SYMBOL);
                    continue;
                }

                String curStr;
                if (curCode < nextCode) curStr = dict.get(curCode);
                else if (curCode == nextCode && prevCode != -1)
                    curStr = prevStr + prevStr.substring(0, 1);
                else break;

                for (char ch : curStr.toCharArray()) BinaryStdOut.write((int) ch, BITS_PER_SYMBOL);

                if (prevCode != -1) {
                    String newStr = prevStr + curStr.substring(0, 1);
                    if (nextCode < L) dict.set(nextCode++, newStr);
                }

                prevCode = curCode;
                prevStr = curStr;
            }
        } catch (NoSuchElementException ignore) {
        } finally {
            BinaryStdOut.close();
        }
    }
}
