package cn.lucanet.enhancement.examples;

import java.util.Comparator;
import java.util.Objects;

public class Test implements Comparator {
    private String s;
    private int i;

    public Test(String s, int i) {
        this.s = s;
        this.i = i;
    }

    @Override
    public int compare(Object arg0, Object arg1) {
        throw new UnsupportedOperationException("TODO");
    }

    public String getS() {
        return s;
    }

    public void setS(String s) {
        this.s = s;
    }

    public int getI() {
        return i;
    }

    public void setI(int i) {
        this.i = i;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Test test = (Test) o;
        return i == test.i && Objects.equals(s, test.s);
    }

    @Override
    public int hashCode() {
        return Objects.hash(s, i);
    }

    @Override
    public String toString() {
        return "Test{" +
            "s='" + s + '\'' +
            ", i=" + i +
            '}';
    }
}
