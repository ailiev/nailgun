package com.martiansoftware.nailgun.examples;
public class Exit {
        public static void main (String[] args) {
                try {
                        System.out.println("Before");
                        System.exit(Integer.parseInt(args[0]));
                        System.out.println("After");
                } catch (Throwable th) {
                        System.err.println("Caught");
                } finally {
                        System.err.println("Finally");
                }
        }
}