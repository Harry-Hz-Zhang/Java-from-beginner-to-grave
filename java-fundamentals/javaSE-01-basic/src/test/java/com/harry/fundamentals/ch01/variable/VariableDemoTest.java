package com.harry.fundamentals.ch01.variable;

import com.harry.fundamentals.ch01.Var.VariableDemo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableDemoTest {

    @Test
    void addShouldReturnSum() {
        assertEquals(5, VariableDemo.add(2, 3));
    }
}
