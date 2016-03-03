package org.kylemoy.PSimJ;

public interface Topology {
	boolean valid(int i, int j);
	
	public static class Default implements Topology {
		@Override
		public boolean valid(int i, int j) {
			return true;
		}
	}
	
	public static class Bus implements Topology {
		@Override
		public boolean valid(int i, int j) {
			return true;
		}
	}
	
	public static class Switch implements Topology {
		@Override
		public boolean valid(int i, int j) {
			return true;
		}
	}
	
	public static class Tree implements Topology {
		@Override
		public boolean valid(int i, int j) {
			return i==(int)((j-1)/2) || j==(int)((i-1)/2);
		}
	}
	
	public static class TopologyViolationException extends Exception {
		public TopologyViolationException (String message) {
			super(message);
		}
	}
}
