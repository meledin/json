package com.github.meledin.json;

public class NumberHolder extends Number {

	private static final long serialVersionUID = 1L;
	private String val;

	public NumberHolder(String val) {
		this.val = val;
	}

	@Override
	public int intValue() {
		return Integer.parseInt(val);
	}

	@Override
	public long longValue() {
		return Long.parseLong(val);
	}

	@Override
	public float floatValue() {
		return Float.parseFloat(val);
	}

	@Override
	public double doubleValue() {
		return Double.parseDouble(val);
	}

	@Override
	public String toString() {
		return val;
	}

}
