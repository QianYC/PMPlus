package main;

public class Runner {
    public static void main(String[] args) {
        int cpuNum=257;
        byte b = (byte) (cpuNum >> 24);
        System.out.println(b);
        b = (byte) ((cpuNum << 8) >> 24);
        System.out.println(b);
        b = (byte) ((cpuNum << 16) >> 24);
        System.out.println(b);
        b = (byte) ((cpuNum << 24) >> 24);
        System.out.println(b);
    }
}
