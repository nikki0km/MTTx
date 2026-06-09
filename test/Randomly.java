package test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

public final class Randomly {

    private static StringGenerationStrategy stringGenerationStrategy = StringGenerationStrategy.SOPHISTICATED; // 字符串生成策略，默认为
                                                                                                               // SOPHISTICATED
    private static int maxStringLength = 10; // 生成字符串的最大长度，默认为 10
    private static boolean useCaching = true; // 是否启用缓存，默认为 true。
    private static int cacheSize = 100; // 缓存的最大大小，默认为 100。
    // 缓存列表：
    private final List<Long> cachedLongs = new ArrayList<>(); // 缓存的 Long 值。
    private final List<String> cachedStrings = new ArrayList<>(); // 缓存的 String 值。
    private final List<Double> cachedDoubles = new ArrayList<>(); // 缓存的 Double 值。
    private final List<byte[]> cachedBytes = new ArrayList<>(); // 缓存的 byte[] 值。
    private Supplier<String> provider;

    private static final ThreadLocal<Random> THREAD_RANDOM = new ThreadLocal<>(); // 线程本地随机数生成器。
    private long seed;

    private void addToCache(long val) { // 将生成的值添加到缓存中。
        if (useCaching && cachedLongs.size() < cacheSize && !cachedLongs.contains(val)) {
            cachedLongs.add(val);
        }
    }

    private void addToCache(double val) {
        if (useCaching && cachedDoubles.size() < cacheSize && !cachedDoubles.contains(val)) {
            cachedDoubles.add(val);
        }
    }

    private void addToCache(String val) {
        if (useCaching && cachedStrings.size() < cacheSize && !cachedStrings.contains(val)) {
            cachedStrings.add(val);
        }
    }

    private Long getFromLongCache() { // 从 Long 缓存中随机获取一个值。
        if (!useCaching || cachedLongs.isEmpty()) { // 如果未启用缓存或缓存为空，则返回 null。
            return null;
        } else { // 否则，从缓存中随机选择一个值。
            return Randomly.fromList(cachedLongs);
        }
    }

    private Double getFromDoubleCache() { // 从 Double 缓存中随机获取一个值。
        if (!useCaching) { // 如果未启用缓存，则返回 null。
            return null;
        }
        if (Randomly.getBoolean() && !cachedLongs.isEmpty()) { // 如果 Long 缓存不为空且随机条件满足，则从 Long 缓存中随机选择一个值并转换为 Double。
            return (double) Randomly.fromList(cachedLongs);
        } else if (!cachedDoubles.isEmpty()) { // 否则，从 Double 缓存中随机选择一个值。
            return Randomly.fromList(cachedDoubles);
        } else {
            return null;
        }
    }

    private String getFromStringCache() { // 从 String 缓存中随机获取一个值。
        if (!useCaching) { // 如果未启用缓存，则返回 null。
            return null;
        }
        if (Randomly.getBoolean() && !cachedLongs.isEmpty()) { // 如果 Long 缓存不为空且随机条件满足，则从 Long 缓存中随机选择一个值并转换为 String。
            return String.valueOf(Randomly.fromList(cachedLongs));
        } else if (Randomly.getBoolean() && !cachedDoubles.isEmpty()) { // 如果 Double 缓存不为空且随机条件满足，则从 Double
                                                                        // 缓存中随机选择一个值并转换为 String。
            return String.valueOf(Randomly.fromList(cachedDoubles));
        } else if (Randomly.getBoolean() && !cachedBytes.isEmpty() // 如果 byte[] 缓存不为空且随机条件满足，则从 byte[] 缓存中随机选择一个值并转换为
                                                                   // String。
                && stringGenerationStrategy == StringGenerationStrategy.SOPHISTICATED) {
            return new String(Randomly.fromList(cachedBytes));
        } else if (!cachedStrings.isEmpty()) { // 如果 String 缓存不为空，则从 String 缓存中随机选择一个值，并根据策略进行转换。
            String randomString = Randomly.fromList(cachedStrings);
            if (Randomly.getBoolean()) {
                return randomString;
            } else {
                return stringGenerationStrategy.transformCachedString(this, randomString);
            }
        } else {
            return null;
        }
    }

    private static boolean cacheProbability() { // 判断是否应该从缓存中获取值。
        // return useCaching && getNextLong(0, 3) == 1;
        return false;
    }

    public String getDateString() {
        // 生成随机日期：2000-01-01 到 2030-12-31
        long startEpochDay = LocalDate.of(2000, 1, 1).toEpochDay();
        long endEpochDay = LocalDate.of(2030, 12, 31).toEpochDay();
        long randomDay = getLong(startEpochDay, endEpochDay);
        LocalDate date = LocalDate.ofEpochDay(randomDay);
        return date.format(DateTimeFormatter.ISO_DATE);
    }

    public String getTimeString() {
        int hour = getInteger(0, 23);
        int minute = getInteger(0, 59);
        int second = getInteger(0, 59);
        LocalTime time = LocalTime.of(hour, minute, second);
        return time.format(DateTimeFormatter.ISO_TIME);
    }

    public String getTimestampString() {
        long startEpochSecond = LocalDateTime.of(2000, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);
        long endEpochSecond = LocalDateTime.of(2030, 12, 31, 23, 59).toEpochSecond(java.time.ZoneOffset.UTC);
        long randomSecond = getLong(startEpochSecond, endEpochSecond);
        LocalDateTime dateTime = LocalDateTime.ofEpochSecond(randomSecond, 0, java.time.ZoneOffset.UTC);
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // CACHING END

    /**
     * a random element in the List
     */
    // public static <T> T fromList(List<T> list) { //从列表中随机选择一个元素。
    // return list.get((int) getNextLong(0, list.size())); //使用 getNextLong
    // 生成一个随机索引，然后从列表中获取对应元素
    // }
    // public static <T> T fromList(List<T> list) {
    // if (list == null || list.isEmpty()) {
    // throw new IllegalArgumentException("Input list cannot be null or empty");
    // }
    // return list.get((int) getNextLong(0, list.size()));
    // }
    public static <T> T fromList(List<T> list) {
        // 参数检查（防御性编程）
        if (list == null) {
            throw new IllegalArgumentException("Input list cannot be null");
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Input list cannot be empty");
        }

        // 安全随机索引（处理超大列表）
        long size = list.size();
        if (size > Integer.MAX_VALUE) {
            throw new ArithmeticException("List size too large: " + size);
        }
        int index = (int) getNextLong(0, size);

        // 返回结果（不再需要边界检查，因为getNextLong保证范围正确）
        return list.get(index);
    }

    @SafeVarargs
    public static <T> T fromOptions(T... options) { // 从多个选项中随机选择一个。
        return options[getNextInt(0, options.length)];
    }

    @SafeVarargs
    public static <T> List<T> nonEmptySubset(T... options) { // 生成一个非空子集。
        int nr = 1 + getNextInt(0, options.length); // options 目标列表或数组。
        return extractNrRandomColumns(Arrays.asList(options), nr); // 调用 extractNrRandomColumns 方法生成子集。
    }

    public static <T> List<T> nonEmptySubset(List<T> columns) { // 生成一个非空子集。
        int nr = 1 + getNextInt(0, columns.size()); // columns：目标列表或数组。
        final List<T> ts = nonEmptySubset(columns, nr);
        return new ArrayList<>(new HashSet<T>(ts));
    }

    public static <T> List<T> nonEmptySubset(List<T> columns, int nr) { // 生成一个非空子集。
        if (nr > columns.size()) {
            throw new AssertionError(columns + " " + nr);
        }
        return extractNrRandomColumns(columns, nr);
    }

    public static <T> List<T> nonEmptySubsetPotentialDuplicates(List<T> columns) { // 生成一个可能包含重复元素的非空子集。
        List<T> arr = new ArrayList<>();
        for (int i = 0; i < Randomly.smallNumber() + 1; i++) {
            arr.add(Randomly.fromList(columns)); // columns：目标列表。
        }
        return arr;
    }

    public static <T> List<T> subset(List<T> columns) { // //生成一个子集（可能为空）。
        int nr = getNextInt(0, columns.size() + 1);
        return extractNrRandomColumns(columns, nr);
    }

    public static <T> List<T> subset(int nr, @SuppressWarnings("unchecked") T... values) { // 生成一个子集（可能为空）。
        List<T> list = new ArrayList<>();
        for (T val : values) {
            list.add(val);
        }
        return extractNrRandomColumns(list, nr);
    }

    public static <T> List<T> subset(@SuppressWarnings("unchecked") T... values) { // 生成一个子集（可能为空）。
        List<T> list = new ArrayList<>();
        for (T val : values) {
            list.add(val);
        }
        return subset(list);
    }

    public static <T> List<T> extractNrRandomColumns(List<T> columns, int nr) { // 从列表中随机提取指定数量的元素。
        assert nr >= 0;
        List<T> selectedColumns = new ArrayList<>();
        List<T> remainingColumns = new ArrayList<>(columns);
        for (int i = 0; i < nr; i++) {
            selectedColumns.add(remainingColumns.remove(getNextInt(0, remainingColumns.size())));
        }
        return selectedColumns;
    }

    /**
     * [0,2]
     */
    public static int smallNumber() {
        // no need to cache for small numbers
        return (int) (Math.abs(getThreadRandom().get().nextGaussian()) * 2);
    }

    public static boolean getBoolean() {
        return getThreadRandom().get().nextBoolean();
    }

    private static ThreadLocal<Random> getThreadRandom() {
        if (THREAD_RANDOM.get() == null) {
            // a static method has been called, before Randomly was instantiated
            THREAD_RANDOM.set(new Random());
        }
        return THREAD_RANDOM;
    }

    public long getInteger() {
        if (smallBiasProbability()) {
            return Randomly.fromOptions(-1L, Long.MAX_VALUE, Long.MIN_VALUE, 1L, 0L);
        } else {
            if (cacheProbability()) {
                Long l = getFromLongCache();
                if (l != null) {
                    return l;
                }
            }
            long nextLong = getThreadRandom().get().nextInt();
            addToCache(nextLong);
            return nextLong;
        }
    }

    public enum StringGenerationStrategy {

        NUMERIC {
            @Override
            public String getString(Randomly r) {
                return getStringOfAlphabet(r, NUMERIC_ALPHABET);
            }

        },
        ALPHANUMERIC {

            @Override
            public String getString(Randomly r) {
                return getStringOfAlphabet(r, ALPHANUMERIC_ALPHABET);

            }

        },
        ALPHANUMERIC_SPECIALCHAR {

            @Override
            public String getString(Randomly r) {
                return getStringOfAlphabet(r, ALPHANUMERIC_SPECIALCHAR_ALPHABET);

            }

        },
        SOPHISTICATED {

            private static final String ALPHABET = ALPHANUMERIC_SPECIALCHAR_ALPHABET;

            @Override
            public String getString(Randomly r) {
                if (smallBiasProbability()) {
                    return Randomly.fromOptions("TRUE", "FALSE", "0.0", "-0.0", "1e500", "-1e500");
                }
                if (cacheProbability()) {
                    String s = r.getFromStringCache();
                    if (s != null) {
                        return s;
                    }
                }

                int n = ALPHABET.length();

                StringBuilder sb = new StringBuilder();

                int chars = getStringLength(r);
                for (int i = 0; i < chars; i++) {
                    if (Randomly.getBooleanWithRatherLowProbability()) {
                        char val = (char) r.getInteger();
                        if (val != 0) {
                            sb.append(val);
                        }
                    } else {
                        sb.append(ALPHABET.charAt(getNextInt(0, n)));
                    }
                }
                while (Randomly.getBooleanWithSmallProbability()) {
                    String[][] pairs = { { "{", "}" }, { "[", "]" }, { "(", ")" } };
                    int idx = (int) Randomly.getNotCachedInteger(0, pairs.length);
                    int left = (int) Randomly.getNotCachedInteger(0, sb.length() + 1);
                    sb.insert(left, pairs[idx][0]);
                    int right = (int) Randomly.getNotCachedInteger(left + 1, sb.length() + 1);
                    sb.insert(right, pairs[idx][1]);
                }
                if (r.provider != null) {
                    while (Randomly.getBooleanWithSmallProbability()) {
                        if (sb.length() == 0) {
                            sb.append(r.provider.get());
                        } else {
                            sb.insert((int) Randomly.getNotCachedInteger(0, sb.length()), r.provider.get());
                        }
                    }
                }

                String s = sb.toString();

                r.addToCache(s);
                return s;
            }

            public String transformCachedString(Randomly r, String randomString) {
                if (Randomly.getBoolean()) {
                    return randomString.toLowerCase();
                } else if (Randomly.getBoolean()) {
                    return randomString.toUpperCase();
                } else {
                    char[] chars = randomString.toCharArray();
                    if (chars.length != 0) {
                        for (int i = 0; i < Randomly.smallNumber(); i++) {
                            chars[r.getInteger(0, chars.length)] = ALPHABET.charAt(r.getInteger(0, ALPHABET.length()));
                        }
                    }
                    return new String(chars);
                }
            }

        };

        private static final String ALPHANUMERIC_SPECIALCHAR_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#<>/.~-+*[]{} ^*?%_|&"; // ()
        private static final String ALPHANUMERIC_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        private static final String NUMERIC_ALPHABET = "0123456789";

        private static int getStringLength(Randomly r) {
            int chars;
            if (Randomly.getBoolean()) {
                chars = Randomly.smallNumber();
            } else {
                chars = r.getInteger(0, maxStringLength);
            }
            return chars;
        }

        private static String getStringOfAlphabet(Randomly r, String alphabet) {
            int chars = getStringLength(r);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chars; i++) {
                sb.append(alphabet.charAt(getNextInt(0, alphabet.length())));
            }
            return sb.toString();
        }

        public abstract String getString(Randomly r);

        public String transformCachedString(Randomly r, String s) {
            return s;
        }

    }

    public String getString() {
        return stringGenerationStrategy.getString(this);
    }

    public byte[] getBytes() {
        int size = Randomly.smallNumber();
        byte[] arr = new byte[size];
        getThreadRandom().get().nextBytes(arr);
        return arr;
    }

    public long getNonZeroInteger() {
        long value;
        if (smallBiasProbability()) {
            return Randomly.fromOptions(-1L, Long.MAX_VALUE, Long.MIN_VALUE, 1L);
        }
        if (cacheProbability()) {
            Long l = getFromLongCache();
            if (l != null && l != 0) {
                return l;
            }
        }
        do {
            value = getInteger();
        } while (value == 0);
        assert value != 0;
        addToCache(value);
        return value;
    }

    public long getPositiveInteger() {
        if (cacheProbability()) {
            Long value = getFromLongCache();
            if (value != null && value >= 0) {
                return value;
            }
        }
        long value;
        if (smallBiasProbability()) {
            value = Randomly.fromOptions(0L, Long.MAX_VALUE, 1L);
        } else {
            value = getNextLong(0, Long.MAX_VALUE);
        }
        addToCache(value);
        assert value >= 0;
        return value;
    }

    public double getFiniteDouble() {
        while (true) {
            double val = getDouble();
            if (Double.isFinite(val)) {
                return val;
            }
        }
    }

    public double getDouble() {
        if (smallBiasProbability()) {
            return Randomly.fromOptions(0.0, -0.0, Double.MAX_VALUE, -Double.MAX_VALUE, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY);
        } else if (cacheProbability()) {
            Double d = getFromDoubleCache();
            if (d != null) {
                return d;
            }
        }
        double value = getThreadRandom().get().nextDouble();
        addToCache(value);
        return value;
    }

    /**
     * 1/100
     */
    private static boolean smallBiasProbability() {
        return getThreadRandom().get().nextInt(100) == 1;
    }

    /**
     * 1/10
     */
    public static boolean getBooleanWithRatherLowProbability() {
        return getThreadRandom().get().nextInt(10) == 1;
    }

    /**
     * 1/100
     */
    public static boolean getBooleanWithSmallProbability() {
        return smallBiasProbability();
    }

    public int getInteger(int left, int right) {
        if (left == right) {
            return left;
        }
        return (int) getLong(left, right);
    }

    // TODO redundant?
    public long getLong(long left, long right) {
        if (left == right) {
            return left;
        }
        return getNextLong(left, right);
    }

    public BigDecimal getRandomBigDecimal() {
        return new BigDecimal(getThreadRandom().get().nextDouble());
    }

    public long getPositiveIntegerNotNull() {
        while (true) {
            long val = getPositiveInteger();
            if (val != 0) {
                return val;
            }
        }
    }

    public static boolean getPercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Percentage must be between 0 and 100");
        }
        return getNextInt(0, 100) < percentage;
    }

    public static long getNonCachedInteger() {
        return getThreadRandom().get().nextLong();
    }

    public static long getPositiveOrZeroNonCachedInteger() {
        return getNextLong(0, Long.MAX_VALUE);
    }

    public static long getNotCachedInteger(int lower, int upper) {
        return getNextLong(lower, upper);
    }

    public Randomly(Supplier<String> provider) {
        this.provider = provider;
    }

    public Randomly() {
        THREAD_RANDOM.set(new Random());
    }

    public Randomly(long seed) {
        this.seed = seed;
        THREAD_RANDOM.set(new Random(seed));
    }

    public static double getUncachedDouble() {
        return getThreadRandom().get().nextDouble();
    }

    public String getChar() {
        while (true) {
            String s = getString();
            if (!s.isEmpty()) {
                return s.substring(0, 1);
            }
        }
    }

    public String getAlphabeticChar() {
        while (true) {
            String s = getChar();
            if (Character.isAlphabetic(s.charAt(0))) {
                return s;
            }
        }
    }

    // see https://stackoverflow.com/a/2546158
    // uniformity does not seem to be important for us
    // SQLancer previously used ThreadLocalRandom.current().nextLong(lower, upper)
    private static long getNextLong(long lower, long upper) {
        if (lower > upper) {
            throw new IllegalArgumentException(lower + " " + upper);
        }
        if (lower == upper) {
            return lower;
        }
        return getThreadRandom().get().longs(lower, upper).findFirst().getAsLong();
    }

    public static int getNextInt(int lower, int upper) {
        return (int) getNextLong(lower, upper);
    }

    public long getSeed() {
        return seed;
    }

    public static int baseInt() {
        return getNextInt(0, 5);
    }
}
