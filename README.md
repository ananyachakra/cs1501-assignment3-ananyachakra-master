# CS 1501 — Assignment 3: Configurable LZW Compression

## 📘 Objective

In this assignment, you will implement a **command-line tool** in Java that performs **LZW compression and expansion** with the following features:

- a custom seed alphabet for initializing the codebook,
- a configurable codebook eviction policy,
- variable codeword width within configurable limits

Your program will allow the user to specify:

* Whether to `compress` or `expand`
* Minimum and maximum codeword widths (`minW`, `maxW`)
* codebook eviction policy (`freeze`, `reset`, `lru`, or `lfu`)
* A custom **alphabet/seed** for initializing the codebook

The program must read input from **standard input** and write output to **standard output**, supporting I/O redirection.  It must use **bit-level I/O** via the given `BinaryStdIn` / `BinaryStdOut` classes.

> 💡 **Note:** You may reuse parts of your **Lab 4 code** (such as the basic LZW compressor, bit-packing logic, or codebook data structures) as a starting point for this assignment. However, you must extend it to support variable codeword widths, the four eviction policies (`reset`, `freeze`, `lru`, `lfu`), and file-based alphabet configuration.

⚙️ Implementation Note: This assignment implements a byte-by-byte version of the LZW compression algorithm. Each symbol in the input stream corresponds to a single byte.

---

## 📂 Folder Structure

```
/TestFiles/             //Files with different types and sizes for testing
LZWTool.java            // Main class to write (This is the ONLY file that you can modify)
BinaryStdIn.java        //Textbook codehandouts for bit-by-bit I/O
BinaryStdOut.java
TSTMod.java             //TST Trie for possible codebook implementation in compression
/alphabets/             //Files with sample alphabets for seeding the codebook
README.md
```

---

## ⚙️ Compilation and Execution

### Compile

```bash
javac *.java
```

### Compress

Your program supports file compression from the command line.
The following example shows how to compress a file named code.txt:

```bash
java LZWTool --mode compress --minW 9 --maxW 16 --policy lru --alphabet alphabets/ascii.txt < TestFiles/code.txt > code.lzw
```

### Expand

Your program supports file expansion from the command line.
The example below shows how to decompress the file code.lzw that was created in the previous step:

```bash
java LZWTool --mode expand < code.lzw > restored.txt
```

---

## 🧩 Command-Line Options and Parsing


Your program supports the following command-line options:


| Option          | Description                                        | Required?    | Default  |
| --------------- | -------------------------------------------------- | ------------ | -------- |
| `--mode`        | `compress` or `expand`                             | ✅            | —        |
| `--minW`        | Minimum codeword width                             | ✅ (compress) | 9        |
| `--maxW`        | Maximum codeword width                             | ✅ (compress) | 16       |
| `--policy`      | Eviction policy: `freeze`, `reset`, `lru`, `lfu` | ✅            | `freeze` |
| `--alphabet`    | path to seed alphabet     | ✅ (compress) | —        |

For expansion, `minW`, `maxW`, `alphabet`, and `policy` are ignored — they are read from the compressed file.

File input and output is supplied using the standard redirect operators for standard I/O: Use "<" to redirect the input from a file and use ">" to redirect the output to a file. 
**Note that the input redirection operator (<) doesn't work with PowerShell under Windows.**

You can parse command-line arguments using a simple loop in `main()`:

```java
for (int i = 0; i < args.length; i++) {
    switch (args[i]) {
        case "--mode":
            mode = args[++i];
            break;
        case "--minW":
            minW = Integer.parseInt(args[++i]);
            break;
        case "--maxW":
            maxW = Integer.parseInt(args[++i]);
            break;
        case "--policy":
            policy = args[++i];
            break;
        case "--alphabet":
            alphabetPath = args[++i];
            break;
        default:
            System.err.println("Unknown argument: " + args[i]);
            System.exit(2);
    }
}
```

Once all arguments are parsed, validate the configuration:

* Ensure required arguments are provided.
* Confirm `minW ≤ maxW`.
* Verify the alphabet file exists.

---

## 📈 Codebook Growth and Variable Codeword Size

The **codebook** begins with entries for all symbols in the seed alphabet and expands dynamically as new phrases are discovered during compression. The **codeword size (`W`)** determines how many bits are used to represent each code. This width starts at the minimum value (`minW`) and increases as the codebook grows.

### Growth Behavior

* **Initial State:** The codebook starts with all symbols from the alphabet. Each symbol has a unique code of width `minW` bits.
* **Growth:** As the encoder encounters new symbol combinations, it assigns the next available code and increments the code count.
* **Width Adjustment:** When the current number of codes equals `2^W`, the codeword width increases by 1 bit, allowing for twice as many unique codes.
* **Upper Bound:** Growth continues until the width reaches the specified `maxW`. At this point, the codebook is full, and an **eviction policy** (`reset`, `freeze`, `lru`, or `lfu`) determines how to proceed.

### Decoding Consistency

During decompression, the decoder must mirror the same codebook growth and width changes exactly as the encoder. The header information ensures both sides agree on `minW`, `maxW`, and policy so that bit boundaries align correctly and codebook updates stay synchronized.


## Compressed File Header

Each compressed file must include a compact header at the beginning of the file. This header stores all metadata required to reconstruct the command-line options used during compression, including the codebook policy, minimum and maximum codeword widths, and the seed alphabet. The header should be written using the minimum number of bytes necessary, ensuring efficient space usage without redundancy. When decompressing, the program reads this header first to automatically restore the same configuration used during compression.


## 🔁 Codebook Eviction Policies

When the **maximum codebook width (`maxW`)** is reached, the codebook is considered **full**, and the eviction policy determines how to proceed. The policy controls whether entries are discarded, replaced, or the codebook is reset. This behavior is critical to maintaining compression efficiency while managing limited code space.

### 1. `reset`

When the codebook reaches full capacity, the encoder (compression program) **reinitializes** the codebook with the original seed alphabet. This allows new patterns to be learned from scratch. It is efficient for files with distinct phases or topic shifts.

### 2. `freeze`

When full, the encoder **stops adding new entries** and continues encoding only with existing codes. This approach ensures deterministic behavior but may reduce compression ratio if new patterns appear after the codebook freezes.

### 3. `lru` (Least Recently Used)

Upon reaching full capacity, the encoder **evicts the least recently used entry** from the codebook and reuses its space for the new pattern. This keeps the codebook adaptive to local patterns and works best when the data exhibits frequent context shifts.

### 4. `lfu` (Least Frequently Used)

When the codebook is full, the encoder removes the **least frequently used entry**, i.e., the one emitted the fewest times. This preserves globally common patterns and works well on files with stable distributions.

In all cases, eviction occurs **only after the codebook has filled to the limit imposed by `maxW`**, ensuring that width growth and code allocation proceed predictably.

## 🔤 Alphabet Configuration

The **alphabet** defines the initial seed symbols used to initialize the LZW codebook before compression or expansion begins. In this assignment, the alphabet must be **read from a file**.

Each line in the alphabet file represents one symbol or token. The order of lines determines the initial code assignments, starting from 0 and increasing sequentially. Blank lines should be ignored, and duplicate entries must be removed deterministically so that both encoder and decoder construct identical dictionaries.

### Example Alphabet File

```
A
B
C
D
E
F
G
H
...
```

### Notes

* The alphabet file is specified using the `--alphabet` command-line argument (e.g., `--alphabet ascii.txt`).
* During compression, this alphabet is written into the compressed file header to allow the decoder to rebuild the exact same seed mapping.
* For simplicity, all inputs and outputs must assume UTF-8 encoding for alphabet entries.
* The alphabet file must be read fully before compression starts.

---

## 🧪 Testing and Verification

You can verify your program by performing a **round-trip test**: compress a file, then immediately expand it, and compare the restored version to the original. Both files must be **bit-for-bit identical** if your encoder and decoder are implemented correctly.

### Example (Linux/macOS)

```bash
java LZWTool --mode compress --alphabet ascii.txt < input.txt > output.lzw
java LZWTool --mode expand < output.lzw > restored.txt
diff input.txt restored.txt
```

If the output from `diff` is empty, your implementation is correct.

### Example (Windows)

Use the `fc` command with binary comparison:

```cmd
java LZWTool --mode compress --alphabet ascii.txt < input.txt > output.lzw
java LZWTool --mode expand < output.lzw > restored.txt
fc /B input.txt restored.txt
```

If `fc /B` reports no differences, your files match exactly.

### Testing Guidelines

* **Start small:** Begin testing with very small files and alphabets (like those shown in class examples) to verify correctness before using larger inputs.
* **Vary parameters:** Test multiple combinations of `minW`, `maxW`, and codebook policies (`reset`, `freeze`, `lru`, `lfu`).
* **Check headers:** Use a hex viewer or `xxd` to confirm that header fields are written correctly into the compressed files.
* **Compression ratio:** Optionally measure and compare compressed file sizes to confirm that adaptive policies behave as expected.
---
## 🐞 Debugging

The most error-prone moments occur when the **codeword width changes** or when a **codebook eviction policy** is triggered. These updates must behave identically during both compression and expansion.

Since output is redirected to a file, standard console printing (`System.out.println()`) will not be visible. Instead, use:

```java
System.err.println()
```

This writes to the **standard error stream**, which still appears in the terminal unless redirected.

To capture debug logs in files for side-by-side comparison:

```bash
java LZWTool --mode compress ... 2> debug-compress.txt
java LZWTool --mode expand ... 2> debug-expand.txt
```

You can then open `debug-compress.txt` and `debug-expand.txt` together to trace encoding and decoding steps.

### Recommended Debug Output

Print the following during early testing:

* Current codeword width (`W`)
* Code value being written or read
* The string or phrase associated with each code
* Each `(code, string)` pair added to the codebook

Focus debugging output on iterations just **before and after width increases or evictions**—these transitions are the most common sources of synchronization bugs.


---
## 🔬 Deliverables

Submit your completed Java project to GradeScope:

* `LZWTool.java` is the ONLY file that you are allowed to submit
---
## **📊 Grading Rubric**

| Item.                                                   |  Points |
| ------------------------------------------------------- | ------- |
| Autograder Tests.                                       | 90      |
| Code style, comments, and modularity                    | 10      |

### **💡 Grading Guidelines**

* Test cases include both visible and hidden scenarios to assess correctness, edge handling, and boundary conditions.
* If your autograder score is below 60%, your code will be manually reviewed for partial credit.

  * However, **manual grading can award no more than 60% of the total autograder points**.
* `Code style, comments, and modularity` is graded manually and includes:

  * Clear and meaningful variable/method names
  * Proper indentation and formatting
  * Use of helper methods to reduce duplication
  * Inline comments explaining non-obvious logic
  * Adherence to Java naming conventions

---



