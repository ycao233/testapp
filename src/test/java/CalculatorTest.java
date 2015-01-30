import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CalculatorTest {

    Calculator underTest;

    @Before
    public void setup() {
        underTest = new Calculator();
    }

    @Test
    public void testAddition() throws Exception {
        assertEquals(underTest.addition(1,2), 3);
    }

    @Test
    public void testSubtraction() throws Exception {
        assertEquals(underTest.subtraction(2,1), 1);
    }
}