package com.pin2pin;

/**
 * This class is for controlled exceptions (expected exceptions).
 */
public class MyBusinessException extends Exception {

    public MyBusinessException(String error){
        super(error);
    }

}
