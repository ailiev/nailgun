package com.martiansoftware.nailgun;

import  java.security.Permission;

import  java.io.PrintStream;

import  org.apache.tools.ant.ExitException;


/**
 * Security manager which does nothing other than trap
 * checkExit, or delegate all non-deprecated methods to
 * a base manager.
 */
public class NGSecurityManager extends SecurityManager {
        private static final ThreadLocal EXIT = new InheritableThreadLocal();
        final SecurityManager base;
        
        /**
         * Construct an NGSecurityManager with the given base.
         * @param base the base security manager, or null for no base.
         */
        public NGSecurityManager (SecurityManager base) {
                this.base = base;
        }

        public void checkExit (int status) {
                if (base != null) {
                        base.checkExit(status);
                }
                
                final PrintStream exit = (PrintStream)EXIT.get();
                
                if (exit != null) {
                        exit.println(status);
                }
                
                throw new NGExitException(status);
        }

        public void checkPermission(Permission perm) {
                if (base != null) {
                        base.checkPermission(perm);
                }
        }
   
        public void checkPermission(Permission perm, Object context) {
                if (base != null) {
                        base.checkPermission(perm, context);
                }
        }
        
        public static void setExit (PrintStream exit) {
                EXIT.set(exit);
        }
}