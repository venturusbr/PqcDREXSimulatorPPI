package br.org.venturus.drexsim.drex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.Locale;

/** Real Digital and Real Tokenizado amounts: integers with 2 decimals ({@code 10050} = R$ 100,50). */
public final class Amounts {

  private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

  private Amounts() {}

  /** {@code 10050} → {@code "100,50"}, {@code 123456789} → {@code "1.234.567,89"}. */
  public static String format(BigInteger amount) {
    NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
    nf.setMinimumFractionDigits(2);
    nf.setMaximumFractionDigits(2);
    return nf.format(new BigDecimal(amount, 2));
  }

  /** {@code "100,50"}, {@code "100.50"} or {@code "100"} → {@code 10050}. */
  public static BigInteger parse(String reais) {
    String s = reais.trim().replace("R$", "").trim();
    if (s.contains(",")) {
      s = s.replace(".", "").replace(',', '.');
    }
    return new BigDecimal(s).movePointRight(2).toBigIntegerExact();
  }
}
