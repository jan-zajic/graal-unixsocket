package com.oracle.svm.core.posix;

/**
 * Represents the level/name of a socket option
 */
public class OptionKey {

	private int level;
  private int name;

  public OptionKey(int level, int name) {
      this.level = level;
      this.name = name;
  }

  int level() {
      return level;
  }

  int name() {
      return name;
  }
	
}
