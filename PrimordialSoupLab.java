/*
 * PrimordialSoupLab.java
 *
 * A single-file Java Swing laboratory recreating the "Computational Life"
 * experiment (Agüera y Arcas et al., 2024): a primordial soup of tiny random
 * programs in which self-replicators appear spontaneously and take over,
 * with no fitness function, no selection and nothing designed in.
 *
 * Each program is 64 bytes of BFF, a variant of Brainfuck whose code and
 * data share one tape, so programs can read and rewrite themselves.
 *
 *   <  >   move head0 left / right          +  -   add / subtract 1 at head0
 *   {  }   move head1 left / right          .      copy byte at head0 -> head1
 *   [  ]   loop while byte at head0 != 0    ,      copy byte at head1 -> head0
 *
 * Every other byte value is inert data. Each epoch, programs are paired up,
 * concatenated into a 128-byte tape, run, and split apart again.
 *
 * Run:   java PrimordialSoupLab.java        (Java 17 or newer)
 *
 * Architect: Mario Gianota
 * Implementation: Claude (Anthropic)
 * Licence: MIT
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.zip.Deflater;

public class PrimordialSoupLab extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new PrimordialSoupLab().setVisible(true);
        });
    }

    // =====================================================================
    //  The BFF interpreter
    // =====================================================================

    static final int PROG = 64, TAPE = 128, MASK = TAPE - 1;
    static final String INSTRUCTIONS = "<>{}+-.,[]";

    static boolean isInstruction(int b) {
        return switch (b) {
            case '<', '>', '{', '}', '+', '-', '.', ',', '[', ']' -> true;
            default -> false;
        };
    }

    /**
     * Runs a 128-byte tape in place. Returns the number of steps executed.
     * This is the "bff_noheads" language of the paper's reference implementation
     * (github.com/paradigms-of-intelligence/cubff): both heads and the instruction
     * pointer start at 0, heads wrap around the tape, and the program halts when the
     * instruction pointer leaves the tape, an unmatched bracket is met, or the step
     * limit is reached. Driven by the reference random-number scheme, this interpreter
     * reproduces the reference run for seed 0 (replicators take over near epoch 2,600).
     */
    static int run(byte[] t, int maxSteps) {
        int ip = 0, h0 = 0, h1 = 0, steps = 0;
        while (ip >= 0 && ip < TAPE && steps < maxSteps) {
            switch (t[ip]) {
                case '<' -> h0 = (h0 - 1) & MASK;
                case '>' -> h0 = (h0 + 1) & MASK;
                case '{' -> h1 = (h1 - 1) & MASK;
                case '}' -> h1 = (h1 + 1) & MASK;
                case '+' -> t[h0]++;
                case '-' -> t[h0]--;
                case '.' -> t[h1] = t[h0];
                case ',' -> t[h0] = t[h1];
                case '[' -> {
                    if (t[h0] == 0) {
                        int depth = 1, p = ip + 1;
                        for (; p < TAPE; p++) {
                            if (t[p] == '[') depth++;
                            else if (t[p] == ']' && --depth == 0) break;
                        }
                        if (p >= TAPE) return steps + 1;      // unmatched: halt
                        ip = p;
                    }
                }
                case ']' -> {
                    if (t[h0] != 0) {
                        int depth = 1, p = ip - 1;
                        for (; p >= 0; p--) {
                            if (t[p] == ']') depth++;
                            else if (t[p] == '[' && --depth == 0) break;
                        }
                        if (p < 0) return steps + 1;          // unmatched: halt
                        ip = p;
                    }
                }
                default -> { }                                // data: no-op
            }
            ip++;
            steps++;
        }
        return steps;
    }

    // =====================================================================
    //  The soup
    // =====================================================================

    enum Mixing {
        SPATIAL("Spatial (neighbours only)"), WELL_MIXED("Well-mixed (anyone meets anyone)");
        final String label;
        Mixing(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    /** SplitMix64, the random-number generator used throughout the reference implementation. */
    static long splitMix64(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /** The reference implementation's default mutation probability: 1 in 4,096 bytes per run. */
    static final double PAPER_MUTATION = 1.0 / 4096;

    static final class Soup {
        final int w, h, n;
        final byte[] data;                 // n programs x 64 bytes
        long epoch = 0;
        long seed;

        volatile Mixing mixing = Mixing.WELL_MIXED;
        volatile double mutationRate = PAPER_MUTATION;   // per byte per run
        volatile int maxSteps = 8192;
        volatile int radius = 2;

        private final int[] order, partner, pa, pb;
        private final ThreadLocal<byte[]> tapes = ThreadLocal.withInitial(() -> new byte[TAPE]);

        Soup(int w, int h, long seed) {
            this.w = w; this.h = h; this.n = w * h;
            data = new byte[n * PROG];
            order = new int[n];
            partner = new int[n];
            pa = new int[n / 2 + 1];
            pb = new int[n / 2 + 1];
            randomise(seed);
        }

        /** The reference implementation's derived seeds: seed(x) = SM(SM(seed) ^ SM(x)). */
        long derive(long x) { return splitMix64(splitMix64(seed) ^ splitMix64(x)); }

        /** Fill the soup with random bytes exactly as the reference implementation does. */
        void randomise(long seed) {
            this.seed = seed;
            long s0 = derive(0);
            long base = (long) PROG * n * s0;
            for (int i = 0; i < data.length; i++) data[i] = (byte) splitMix64(base + i);
            epoch = 0;
        }

        /** One epoch: pair everyone up, mutate, run every pair, split. */
        void epoch() {
            int pairs = 0;
            if (mixing == Mixing.WELL_MIXED) {
                // Reference shuffle (Fisher-Yates driven by SplitMix64), then pair neighbours in the list
                for (int i = 0; i < n; i++) order[i] = i;
                long len = n;
                for (int i = n - 1; i >= 0; i--) {
                    int j = (int) Long.remainderUnsigned(splitMix64(derive(epoch * len + i)), i + 1);
                    int t = order[i]; order[i] = order[j]; order[j] = t;
                }
                for (int k = 0; k + 1 < n; k += 2) { pa[pairs] = order[k]; pb[pairs] = order[k + 1]; pairs++; }
            } else {
                // Spatial: each program, in random order, picks a random unpaired neighbour
                Random rng = new Random(derive(epoch));
                Arrays.fill(partner, -1);
                for (int i = 0; i < n; i++) order[i] = i;
                for (int i = n - 1; i > 0; i--) { int j = rng.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
                int r = radius, span = 2 * r + 1;
                for (int k = 0; k < n; k++) {
                    int a = order[k];
                    if (partner[a] >= 0) continue;
                    int ax = a % w, ay = a / w;
                    for (int attempt = 0; attempt < 12; attempt++) {
                        int dx = rng.nextInt(span) - r, dy = rng.nextInt(span) - r;
                        if (dx == 0 && dy == 0) continue;
                        int b = Math.floorMod(ay + dy, h) * w + Math.floorMod(ax + dx, w);
                        if (partner[b] >= 0) continue;
                        partner[a] = b; partner[b] = a;
                        pa[pairs] = a; pb[pairs] = b;
                        pairs++;
                        break;
                    }
                }
            }

            final int np = pairs, lim = maxSteps;
            final long es = derive(epoch), nn = n;
            final long mprob = Math.round(mutationRate * (1L << 30));
            IntStream.range(0, np).parallel().forEach(p -> {
                byte[] t = tapes.get();
                int a = pa[p] * PROG, b = pb[p] * PROG;
                System.arraycopy(data, a, t, 0, PROG);
                System.arraycopy(data, b, t, PROG, PROG);
                // Reference mutation: each byte of the joined tape, before the run
                if (mprob > 0)
                    for (int i = 0; i < TAPE; i++) {
                        long rng = splitMix64((nn * es + p) * TAPE + i);
                        if (((rng >>> 8) & ((1L << 30) - 1)) < mprob) t[i] = (byte) rng;
                    }
                run(t, lim);
                System.arraycopy(t, 0, data, a, PROG);
                System.arraycopy(t, PROG, data, b, PROG);
            });
            epoch++;
        }

        void put(int index, byte[] prog) { System.arraycopy(prog, 0, data, index * PROG, PROG); }

        byte[] get(int index) { return Arrays.copyOfRange(data, index * PROG, index * PROG + PROG); }

        void copyParamsFrom(Soup o) {
            mixing = o.mixing; mutationRate = o.mutationRate; maxSteps = o.maxSteps; radius = o.radius;
        }
    }

    // =====================================================================
    //  Measurements
    // =====================================================================

    record Stats(long epoch, double shannon, double compressed, double highOrder,
                 double instrFrac, double zeroFrac, int unique, double topShare, byte[] topProgram) { }

    static Stats measure(Soup s) {
        byte[] d = s.data;
        long[] hist = new long[256];
        for (byte b : d) hist[b & 0xFF]++;
        double len = d.length, H = 0;
        long instr = 0;
        for (int v = 0; v < 256; v++) {
            if (hist[v] == 0) continue;
            double p = hist[v] / len;
            H -= p * Math.log(p) / Math.log(2);
            if (isInstruction(v)) instr += hist[v];
        }
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(d);
        def.finish();
        byte[] buf = new byte[65536];
        long clen = 0;
        while (!def.finished()) clen += def.deflate(buf);
        def.end();
        double C = clen * 8.0 / len;

        HashMap<String, Integer> counts = new HashMap<>(s.n * 2);
        String top = null;
        int topN = 0;
        for (int i = 0; i < s.n; i++) {
            String k = new String(d, i * PROG, PROG, StandardCharsets.ISO_8859_1);
            int c = counts.merge(k, 1, Integer::sum);
            if (c > topN) { topN = c; top = k; }
        }
        return new Stats(s.epoch, H, C, H - C, instr / len, hist[0] / len, counts.size(),
                (double) topN / s.n, top == null ? new byte[PROG] : top.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Show a program as text: instructions as themselves, zero as '0', other bytes as '·'. */
    static String show(byte[] p) {
        StringBuilder sb = new StringBuilder(p.length);
        for (byte b : p) {
            int v = b & 0xFF;
            sb.append(isInstruction(v) ? (char) v : v == 0 ? '0' : '·');
        }
        return sb.toString();
    }

    // =====================================================================
    //  Colours
    // =====================================================================

    static final int[] PALETTE = new int[256];
    static {
        for (int v = 0; v < 256; v++) {
            int g = 34 + (v * 37) / 255;                 // inert data: dim grey, faintly varying
            PALETTE[v] = (g << 16) | (g << 8) | (g + 6);
        }
        PALETTE[0]   = 0x000000;                           // zero byte: black
        PALETTE['<'] = 0x3D7BE0; PALETTE['>'] = 0x6FA8FF;  // head0 moves: blues
        PALETTE['{'] = 0x2FA866; PALETTE['}'] = 0x6FD89A;  // head1 moves: greens
        PALETTE['+'] = 0xE08A2E; PALETTE['-'] = 0xB8641E;  // arithmetic: oranges
        PALETTE['.'] = 0xE8435A; PALETTE[','] = 0xFF8FA6;  // copy: reds / pinks
        PALETTE['['] = 0xF2E15A; PALETTE[']'] = 0xC9B83A;  // loops: yellows
    }

    /** Each program becomes an 8x8 tile of its bytes. */
    static void render(Soup s, int[] pixels) {
        int W = s.w * 8;
        byte[] d = s.data;
        for (int i = 0; i < s.n; i++) {
            int ox = (i % s.w) * 8, oy = (i / s.w) * 8, base = i * PROG;
            for (int k = 0; k < PROG; k++)
                pixels[(oy + (k >> 3)) * W + ox + (k & 7)] = PALETTE[d[base + k] & 0xFF];
        }
    }

    // =====================================================================
    //  Application
    // =====================================================================

    private volatile Soup soup;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true, singleStep = false;
    private volatile int epochsPerFrame = 8;
    private volatile BufferedImage frame;
    private volatile Stats stats;
    private volatile double epochsPerSecond;
    private volatile int inspected = -1;
    private volatile boolean inoculateMode = false;
    private volatile long takeoverEpoch = -1;

    private final ArrayList<Stats> history = new ArrayList<>();
    private final Object histLock = new Object();
    private final DefaultListModel<String> events = new DefaultListModel<>();

    private final ViewPanel view = new ViewPanel();
    private final Chart chart = new Chart();
    private final JLabel status = new JLabel(" ");
    private final JTextArea inspector = new JTextArea(7, 30);
    private final JTextArea dominant = new JTextArea(3, 30);

    private final JComboBox<Mixing> mixBox = new JComboBox<>(Mixing.values());
    private final JComboBox<String> sizeBox = new JComboBox<>(new String[]{"64 × 36  (2,304 programs)", "128 × 72  (9,216)", "240 × 135  (32,400)", "512 × 256  (131,072 — paper size)"});
    private final ParamSlider mutSlider = new ParamSlider("Mutation (× the paper's 1 in 4,096 bytes)", 0, 4, 1, "%.2f×");
    private final ParamSlider stepSlider = new ParamSlider("Step limit per run", 256, 8192, 8192, "%.0f");
    private final ParamSlider speedSlider = new ParamSlider("Epochs per frame", 1, 32, 8, "%.0f");
    private final JToggleButton playButton = new JToggleButton("Pause", true);
    private final JToggleButton inoculateButton = new JToggleButton("Inoculate");
    private final JTextField seedField = new JTextField("0", 8);

    /**
     * A known BFF self-replicator for the Inoculate tool: a 16-byte palindromic copier
     * padded with '['. When it runs first it copies itself (reversed, which for a
     * palindrome is the same) over its partner. Verified in testing: a 5 x 5 colony
     * reliably spreads through a 128 x 72 spatial soup, and its mutated descendants
     * then take over from it.
     */
    static final byte[] REPLICATOR = makeReplicator();

    static byte[] makeReplicator() {
        String core = "[[{.>]-]]-]>.{[[";
        byte[] p = new byte[PROG];
        Arrays.fill(p, (byte) '[');
        byte[] c = core.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(c, 0, p, PROG - c.length, c.length);
        return p;
    }

    public PrimordialSoupLab() {
        super("Primordial Soup Lab — watching life-like replicators emerge from random code");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        soup = new Soup(512, 256, 0);        // the paper's soup size; seed 0 = the reference default
        sizeBox.setSelectedIndex(3);
        mixBox.setSelectedItem(Mixing.WELL_MIXED);
        buildUI();
        recordStats();

        Thread worker = new Thread(this::loop, "soup-sim");
        worker.setDaemon(true);
        worker.start();
        new javax.swing.Timer(50, e -> { view.repaint(); chart.repaint(); updatePanels(); }).start();
        pack();
        setLocationRelativeTo(null);
    }

    private void loop() {
        Soup current = null;
        int[] pixels = null;
        BufferedImage[] bufs = new BufferedImage[2];
        int bi = 0;
        long lastT = System.nanoTime(), epochsSince = 0;
        while (true) {
            long start = System.nanoTime();
            Runnable t;
            while ((t = tasks.poll()) != null) t.run();
            Soup s = soup;
            if (s != current) {
                current = s;
                for (int k = 0; k < 2; k++) bufs[k] = new BufferedImage(s.w * 8, s.h * 8, BufferedImage.TYPE_INT_RGB);
                pixels = new int[s.w * 8 * s.h * 8];
            }
            boolean advance = running || singleStep;
            if (advance) {
                int e = singleStep && !running ? 1 : epochsPerFrame;
                singleStep = false;
                for (int k = 0; k < e; k++) {
                    s.epoch();
                    epochsSince++;
                    if (s.epoch % Math.max(8, s.n / 4096) == 0) recordStats();
                }
            }
            render(s, pixels);
            BufferedImage img = bufs[bi];
            bi ^= 1;
            img.setRGB(0, 0, s.w * 8, s.h * 8, pixels, 0, s.w * 8);
            frame = img;

            long now = System.nanoTime();
            if (now - lastT > 500_000_000L) { epochsPerSecond = epochsSince * 1e9 / (now - lastT); epochsSince = 0; lastT = now; }
            long sleep = 30 - (System.nanoTime() - start) / 1_000_000;
            try { Thread.sleep(Math.max(1, sleep)); } catch (InterruptedException ex) { return; }
        }
    }

    private void recordStats() {
        Soup s = soup;
        Stats st = measure(s);
        stats = st;
        synchronized (histLock) {
            history.add(st);
            if (history.size() > 4000) {                    // thin out old history, keep the shape
                ArrayList<Stats> thin = new ArrayList<>();
                for (int i = 0; i < history.size(); i += 2) thin.add(history.get(i));
                history.clear();
                history.addAll(thin);
            }
        }
        if (takeoverEpoch < 0 && st.highOrder() > 1.0) {
            takeoverEpoch = st.epoch();
            String msg = String.format("Epoch %,d: replicators are taking over (complexity %.2f bits/byte)", st.epoch(), st.highOrder());
            SwingUtilities.invokeLater(() -> events.add(0, msg));
        }
    }

    private void resetSoup(boolean newSeed) {
        long chosen;
        if (newSeed) {
            chosen = new Random().nextInt(1_000_000);
            seedField.setText(String.valueOf(chosen));
        } else {
            try { chosen = Long.parseLong(seedField.getText().trim()); }
            catch (NumberFormatException ex) { chosen = 0; seedField.setText("0"); }
        }
        final long sd = chosen;
        tasks.add(() -> {
            Soup s = soup;
            s.randomise(sd);
            clearHistory();
            String msg = String.format("New soup, seed %d", s.seed);
            SwingUtilities.invokeLater(() -> events.add(0, msg));
            recordStats();
        });
    }

    private void clearHistory() {
        synchronized (histLock) { history.clear(); }
        takeoverEpoch = -1;
    }

    private void changeSize(int idx) {
        int[][] dims = {{64, 36}, {128, 72}, {240, 135}, {512, 256}};
        int w = dims[idx][0], h = dims[idx][1];
        tasks.add(() -> {
            Soup old = soup;
            if (old.w == w) return;
            Soup s = new Soup(w, h, old.seed);
            s.copyParamsFrom(old);
            soup = s;
            inspected = -1;
            clearHistory();
            recordStats();
            String msg = String.format("New %d × %d soup (%,d programs), seed %d", w, h, s.n, s.seed);
            SwingUtilities.invokeLater(() -> events.add(0, msg));
        });
    }

    private void pushParams() {
        Soup s = soup;
        s.mutationRate = PAPER_MUTATION * Math.round(mutSlider.value() * 100) / 100.0;
        s.maxSteps = (int) Math.round(stepSlider.value());
        s.mixing = (Mixing) mixBox.getSelectedItem();
        epochsPerFrame = (int) Math.round(speedSlider.value());
    }

    // =====================================================================
    //  UI
    // =====================================================================

    private void buildUI() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(new EmptyBorder(8, 8, 8, 14));

        JPanel soupPanel = section("Soup");
        JPanel r1 = row("Mixing: ", mixBox);
        mixBox.addActionListener(e -> { if (soup != null) pushParams(); });
        soupPanel.add(r1);
        JPanel r2 = row("Size: ", sizeBox);
        sizeBox.addActionListener(e -> changeSize(sizeBox.getSelectedIndex()));
        soupPanel.add(r2);
        JButton seedBtn = new JButton("Restart");
        seedBtn.setToolTipText("Restart the soup from this seed. Seed 0 with the paper-size soup matches the paper's reference code.");
        seedBtn.addActionListener(e -> resetSoup(false));
        JPanel r3 = row("Seed: ", seedField);
        r3.add(Box.createHorizontalStrut(6));
        r3.add(seedBtn);
        soupPanel.add(r3);
        for (ParamSlider ps : new ParamSlider[]{mutSlider, stepSlider, speedSlider}) { ps.onChange(this::pushParams); soupPanel.add(ps); }
        JPanel buttons = new JPanel(new GridLayout(2, 3, 4, 4));
        JButton stepBtn = new JButton("Step");
        JButton resetBtn = new JButton("New soup");
        JButton saveBtn = new JButton("Save PNG");
        JButton aboutBtn = new JButton("About");
        playButton.addActionListener(e -> { running = playButton.isSelected(); playButton.setText(running ? "Pause" : "Play"); });
        stepBtn.addActionListener(e -> singleStep = true);
        resetBtn.addActionListener(e -> resetSoup(true));
        inoculateButton.addActionListener(e -> inoculateMode = inoculateButton.isSelected());
        inoculateButton.setToolTipText("Then click the soup to plant a small colony of a known self-replicator");
        saveBtn.addActionListener(e -> savePng());
        aboutBtn.addActionListener(e -> showAbout());
        buttons.add(playButton); buttons.add(stepBtn); buttons.add(resetBtn);
        buttons.add(inoculateButton); buttons.add(saveBtn); buttons.add(aboutBtn);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        soupPanel.add(Box.createVerticalStrut(4));
        soupPanel.add(buttons);
        controls.add(soupPanel);

        JPanel measures = section("Measurements");
        JLabel cl = new JLabel("<html><span style='color:#e8a33a'>■</span> complexity (high-order entropy)  "
                + "<span style='color:#6fa8ff'>■</span> instructions  <span style='color:#bbbbbb'>■</span> zeros</html>");
        cl.setFont(cl.getFont().deriveFont(11f));
        cl.setAlignmentX(LEFT_ALIGNMENT);
        measures.add(cl);
        chart.setAlignmentX(LEFT_ALIGNMENT);
        measures.add(chart);
        JLabel dl = new JLabel("Most common program:");
        dl.setFont(dl.getFont().deriveFont(11f));
        dl.setAlignmentX(LEFT_ALIGNMENT);
        measures.add(Box.createVerticalStrut(4));
        measures.add(dl);
        styleMono(dominant);
        measures.add(dominant);
        controls.add(measures);

        JPanel insp = section("Inspector (click a program)");
        styleMono(inspector);
        inspector.setText("Click any tile in the soup to read its 64 bytes.");
        insp.add(inspector);
        controls.add(insp);

        JPanel ev = section("Events");
        JList<String> list = new JList<>(events);
        list.setFont(list.getFont().deriveFont(11f));
        JScrollPane evs = new JScrollPane(list);
        evs.setPreferredSize(new Dimension(300, 90));
        evs.setAlignmentX(LEFT_ALIGNMENT);
        ev.add(evs);
        controls.add(ev);

        JPanel legend = section("Colour key");
        legend.add(new LegendPanel());
        controls.add(legend);
        controls.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(400, 820));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        status.setBorder(new EmptyBorder(4, 10, 4, 10));
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll, BorderLayout.WEST);
        getContentPane().add(view, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);

        events.add(0, String.format("New soup, seed %d", soup.seed));
    }

    private static void styleMono(JTextArea a) {
        a.setEditable(false);
        a.setLineWrap(true);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setBackground(new Color(0x1c, 0x1e, 0x22));
        a.setForeground(new Color(0xE6, 0xE6, 0xE6));
        a.setBorder(new EmptyBorder(4, 6, 4, 6));
        a.setAlignmentX(LEFT_ALIGNMENT);
    }

    private static JPanel row(String label, JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        p.add(new JLabel(label));
        p.add(c);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 6, 6, 6)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void updatePanels() {
        Soup s = soup;
        Stats st = stats;
        if (st != null) {
            dominant.setText(String.format("%s%nshare %.2f%%  ·  %,d distinct programs", show(st.topProgram()),
                    100 * st.topShare(), st.unique()));
            status.setText(String.format("epoch %,9d   %d × %d = %,d programs   %5.1f epochs/s   complexity %.2f   instructions %.1f%%   zeros %.1f%%",
                    s.epoch, s.w, s.h, s.n, epochsPerSecond, st.highOrder(), 100 * st.instrFrac(), 100 * st.zeroFrac()));
        }
        int i = inspected;
        if (i >= 0 && i < s.n) {
            byte[] p = s.get(i);
            int instr = 0;
            for (byte b : p) if (isInstruction(b & 0xFF)) instr++;
            inspector.setText(String.format("Program %d  (x %d, y %d)%n%s%n%n%d instructions, %d data bytes%n"
                    + "Key: instructions as themselves, 0 = zero byte, · = other data",
                    i, i % s.w, i / s.w, show(p), instr, PROG - instr));
        }
    }

    private void savePng() {
        BufferedImage img = frame;
        if (img == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("primordial-soup.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".png")) f = new File(f.getPath() + ".png");
        try { ImageIO.write(img, "png", f); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage()); }
    }

    private void showAbout() {
        String html = """
            <html><body style='width:480px; font-family:sans-serif'>
            <h2>A primordial soup of code</h2>
            <p>Every tile in the picture is a tiny computer program, 64 bytes long, drawn as an
            8 × 8 square: one coloured pixel per byte. At the start every byte is random. Only 10 of
            the 256 possible byte values are instructions; the rest are inert data, shown in grey.</p>
            <p>Each <b>epoch</b>, programs are paired up with a neighbour, glued end to end into a
            128-byte tape, and run. Because code and data share the same tape, a program can
            read and overwrite itself <i>and its partner</i>. Then the tape is cut in two and both
            halves go back into the soup. That's all. There is no goal, no fitness score and no
            selection.</p>
            <p>For a long time nothing much happens. Then, if you're lucky and patient, a program
            appears that happens to copy itself over its partner. Now there are two copies, then
            four, then eight. A wave of near-identical tiles sweeps across the soup and the
            <b>complexity</b> curve jumps. Life, of a sort, has begun.</p>
            <p><b>How long is "patient"?</b> The lab starts with the paper's settings: 131,072
            programs, well mixed, seed 0. It uses the same random numbers as the paper's own
            software, so this soup always comes alive at around <b>epoch 2,600</b>. Other seeds
            are a lottery: in the paper, about 40% of runs produced replicators within 16,000
            epochs, and smaller soups get fewer lucky draws. Change the seed to try your luck, or
            use <b>Inoculate</b> (click it, then click the soup) to plant a small colony of a known
            replicator by hand.</p>
            <p><b>The language (BFF)</b><br>
            <tt>&lt; &gt;</tt> move the read head &nbsp; <tt>{ }</tt> move the write head<br>
            <tt>+ -</tt> change the byte under the read head<br>
            <tt>.</tt> copy read head → write head &nbsp; <tt>,</tt> copy write head → read head<br>
            <tt>[ ]</tt> loop while the byte under the read head is not zero</p>
            <p><b>Complexity</b> is the "high-order entropy" used in the paper: how varied the
            bytes are, minus how well the soup compresses. A random soup is varied but has no
            structure; a soup full of replicators is varied <i>and</i> highly structured.</p>
            <p>Try <b>Inoculate</b> to plant a known replicator by hand, the <b>Inspector</b> to read any
            program, and <b>Well-mixed</b> mode to let every program meet every other.</p>
            <p>Based on <i>Computational Life: How Well-formed, Self-replicating Programs Emerge
            from Simple Interaction</i> by Blaise Agüera y Arcas and colleagues (2024).</p>
            <p style='color:#666'>Architect: Mario Gianota · Implementation: Claude (Anthropic) · MIT licence</p>
            </body></html>""";
        JOptionPane.showMessageDialog(this, new JLabel(html), "About Primordial Soup Lab", JOptionPane.PLAIN_MESSAGE);
    }

    // =====================================================================
    //  Widgets
    // =====================================================================

    static final class ParamSlider extends JPanel {
        private static final int TICKS = 1000;
        private final double min, max;
        private final String fmt;
        private final JSlider slider = new JSlider(0, TICKS);
        private final JLabel valueLabel = new JLabel();
        private Runnable listener = () -> { };

        ParamSlider(String name, double min, double max, double initial, String fmt) {
            this.min = min; this.max = max; this.fmt = fmt;
            setLayout(new BorderLayout());
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            JPanel top = new JPanel(new BorderLayout());
            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(12f));
            valueLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            top.add(nameLabel, BorderLayout.WEST);
            top.add(valueLabel, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(slider, BorderLayout.CENTER);
            slider.addChangeListener(e -> { refresh(); listener.run(); });
            setValue(initial);
        }
        double value() { return min + (max - min) * slider.getValue() / TICKS; }
        void setValue(double v) { slider.setValue((int) Math.round((v - min) / (max - min) * TICKS)); refresh(); }
        void onChange(Runnable r) { listener = r; }
        private void refresh() { valueLabel.setText(String.format(fmt, value())); }
    }

    final class ViewPanel extends JPanel {
        ViewPanel() {
            setPreferredSize(new Dimension(1024, 600));
            setBackground(new Color(0x0c, 0x0d, 0x10));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { click(e); }
            });
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        private Rectangle area() {
            Soup s = soup;
            double aspect = (double) s.w / s.h;
            int W = getWidth() - 16, H = getHeight() - 16;
            int w = W, h = (int) (W / aspect);
            if (h > H) { h = H; w = (int) (H * aspect); }
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private void click(MouseEvent e) {
            Soup s = soup;
            Rectangle r = area();
            if (!r.contains(e.getPoint())) return;
            int x = (int) ((e.getX() - r.x) * (double) s.w / r.width);
            int y = (int) ((e.getY() - r.y) * (double) s.h / r.height);
            int idx = Math.min(s.n - 1, y * s.w + x);
            if (inoculateMode) {
                tasks.add(() -> {
                    if (soup != s) return;
                    // A single copy is often destroyed before it can spread, so plant a small colony
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            s.put(Math.floorMod(y + dy, s.h) * s.w + Math.floorMod(x + dx, s.w), REPLICATOR);
                    String msg = String.format("Epoch %,d: 5 × 5 colony of replicators planted at (%d, %d)", s.epoch, x, y);
                    SwingUtilities.invokeLater(() -> events.add(0, msg));
                });
            }
            inspected = idx;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            BufferedImage img = frame;
            if (img == null) return;
            Rectangle r = area();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, r.x, r.y, r.width, r.height, null);
            int i = inspected;
            Soup s = soup;
            if (i >= 0 && i < s.n) {
                double cw = (double) r.width / s.w, ch = (double) r.height / s.h;
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRect((int) (r.x + (i % s.w) * cw) - 1, (int) (r.y + (i / s.w) * ch) - 1, (int) Math.ceil(cw) + 2, (int) Math.ceil(ch) + 2);
            }
        }
    }

    final class Chart extends JPanel {
        Chart() {
            setPreferredSize(new Dimension(300, 130));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
            setBackground(new Color(0x1a, 0x1c, 0x20));
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            List<Stats> h;
            synchronized (histLock) { h = new ArrayList<>(history); }
            int w = getWidth(), ht = getHeight();
            if (h.size() < 2) { g.setColor(Color.GRAY); g.drawString("waiting…", 8, ht / 2); return; }
            long e0 = h.get(0).epoch(), e1 = Math.max(e0 + 1, h.get(h.size() - 1).epoch());
            double maxC = 1.5;
            for (Stats s : h) maxC = Math.max(maxC, s.highOrder());
            if (takeoverEpoch >= 0) {
                int x = (int) ((takeoverEpoch - e0) / (double) (e1 - e0) * (w - 1));
                g.setColor(new Color(255, 255, 255, 40));
                g.fillRect(x, 0, w - x, ht);
            }
            plot(g, h, e0, e1, w, ht, s -> s.zeroFrac(), 1.0, new Color(190, 190, 190));
            plot(g, h, e0, e1, w, ht, s -> s.instrFrac(), 1.0, new Color(111, 168, 255));
            plot(g, h, e0, e1, w, ht, s -> Math.max(0, s.highOrder()), maxC, new Color(232, 163, 58));
            g.setColor(new Color(200, 200, 200));
            g.setFont(g.getFont().deriveFont(10f));
            g.drawString(String.format("epoch %,d", e1), w - 90, ht - 4);
        }

        private void plot(Graphics2D g, List<Stats> h, long e0, long e1, int w, int ht,
                          java.util.function.ToDoubleFunction<Stats> f, double max, Color c) {
            g.setColor(c);
            g.setStroke(new BasicStroke(1.6f));
            int px = -1, py = -1;
            for (Stats s : h) {
                int x = (int) ((s.epoch() - e0) / (double) (e1 - e0) * (w - 1));
                int y = (int) (ht - 6 - f.applyAsDouble(s) / max * (ht - 14));
                if (px >= 0) g.drawLine(px, py, x, y);
                px = x; py = y;
            }
        }
    }

    static final class LegendPanel extends JPanel {
        private static final Object[][] ITEMS = {
            {"<  >", "move read head", '<'}, {"{  }", "move write head", '{'},
            {"+  -", "change a byte", '+'}, {".  ,", "copy between heads", '.'},
            {"[  ]", "loop", '['}, {"0", "zero byte", (char) 0}, {"·", "inert data", (char) 100}};

        LegendPanel() {
            setPreferredSize(new Dimension(300, ITEMS.length * 18 + 4));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ITEMS.length * 18 + 4));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int i = 0; i < ITEMS.length; i++) {
                int y = 2 + i * 18;
                char c = (Character) ITEMS[i][2];
                g.setColor(new Color(PALETTE[c]));
                g.fillRect(2, y + 2, 12, 12);
                if (c == '<' || c == '{' || c == '+' || c == '.' || c == '[') {
                    char c2 = ((String) ITEMS[i][0]).charAt(3);
                    g.setColor(new Color(PALETTE[c2]));
                    g.fillRect(16, y + 2, 12, 12);
                }
                g.setColor(getForeground());
                g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
                g.drawString((String) ITEMS[i][0], 36, y + 13);
                g.setFont(getFont().deriveFont(12f));
                g.drawString((String) ITEMS[i][1], 80, y + 13);
            }
        }
    }
}
