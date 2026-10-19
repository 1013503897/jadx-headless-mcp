package demo;

public class Outer {
    public int outerField = 1;

    public void outerMethod(String s) {}

    public static class Nested {
        public void nestedMethod() {}
    }

    public class Inner {
        public int innerField;
        public void innerMethod() {}
    }
}
