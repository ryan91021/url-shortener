package com.urlshort.shortener.util;

public final class Base62Encoder {
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();
    private Base62Encoder(){

    }

    public static String encode(long id){
        if(id < 0){
            throw new IllegalArgumentException("id must be non-negative");
        }

        if(id == 0){
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        while(id > 0){
            sb.append(ALPHABET.charAt((int)(id % BASE)));
            id /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code){
        if(code == null || code.isEmpty()){
            throw new IllegalArgumentException("code must not be empty");
        }
        long result = 0;
        for(char c: code.toCharArray()){
            int digit = ALPHABET.indexOf(c);
            if (digit < 0){
                throw new IllegalArgumentException("invalid character: " + c);
            }
            result = result * BASE + digit;
        }

        return result;
    }

}
