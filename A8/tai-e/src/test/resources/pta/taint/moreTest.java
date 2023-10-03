class moreTest {
    public static void main(String[] args) {
        String taint = SourceSink.source();
        String s = "abc" + taint + "xyz";
        SourceSink.sink(s);
    }
}