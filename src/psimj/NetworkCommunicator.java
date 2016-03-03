package psimj;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.NotBoundException;
import java.util.ArrayList;
import java.util.List;

public class NetworkCommunicator implements Communicator {
	public static final int ALL_NODES = Integer.MIN_VALUE;
	public static final int PORT_RANGE_START = 8195;
	private NodeSocket[] nodes;
	private int port;
	private int rank;
	private int nprocs;
	private Topology topology;
	
	/**
	 * Sets up a distributed environment using a PSimJ pool
	 * @param poolHost address of the PSimJ pool
	 * @param poolPort listening port of the PSimJ pool
	 * @param n number of processors
	 * @param topology topology of the network, although this should always be a Switch
	 * @param task the task to be parallelized
	 * @throws IOException
	 * @throws NotBoundException
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 */
	public static void init(String poolHost, int poolPort, int n, Topology topology, Class<? extends Task> task) throws IOException, NotBoundException, InstantiationException, IllegalAccessException {
		
		//Connect to pool host
		NodeSocket host = new NodeSocket(poolHost, poolPort);
		while (!host.isReady()) {
			try {Thread.sleep(1000);} catch (InterruptedException e) {}
		}
		
		// Request n nodes from pool
		host.os.writeInt(n);
		
		// Pool responds with rank - should be 0
		int rank = host.is.readInt();
		if (rank != 0) {
			System.err.println("Insufficient nodes for pool size " + host);
			return;
		}
		
		// Receive node info from pool
		List<String> ipList = new ArrayList<String>();
		int size = host.is.readInt();
		for (int i = 0; i < size; i++) {
			int bufSize = host.is.readInt();
			byte[] buf = new byte[bufSize];
			host.is.readFully(buf);
			ipList.add(new String(buf));
		}

		// Initialize communications with all nodes
		Communicator comm = new NetworkCommunicator(ipList, rank, topology);
		
		// Build class definition from runnable
		SerializedClass cls = SerializedClass.fromClass(task);
		
		// Broadcast class definition
		cls = comm.one2all_broadcast(0, cls, SerializedClass.class);
		
		// Instantiate class
		Task instance = task.newInstance();
		
		// Go
		instance.run(comm);
		
		System.out.println("Done!");
	}
	public NetworkCommunicator(List<String> ips, int rank, Topology topology) throws IOException {
		nodes = new NodeSocket[ips.size()];
		this.port = PORT_RANGE_START + rank;
		this.rank = rank;
		nprocs = ips.size();
		this.topology = topology;
		
		// Create dummy connection with self
		nodes[rank] = new NodeSocket();
		
		// Initiate connections with all ranks below me
		for (int i = rank + 1; i < ips.size(); i++) {
			nodes[i] = new NodeSocket(ips.get(i), PORT_RANGE_START + i, rank);
		}
		
		// Accept connections from all ranks above me
		ServerSocket serverSocket = new ServerSocket(port);
		serverSocket.setSoTimeout(500);
		
		// Listen for new nodes
		while (!isReady()) {
			try {
				Socket nodeSocket = serverSocket.accept();
				NodeSocket node = new NodeSocket(nodeSocket);
				int nRank = node.is.readInt();
				nodes[nRank] = node;
			} catch (IOException e) {
				// Timed out, try again
			}
		}
		serverSocket.close();
	}

	public boolean isReady() {
		for (NodeSocket node : nodes) {
			if (node == null || !node.isReady()) {
				return false;
			}
		}
		return true;
	}
	
	@Override
	public int rank() {
		return rank;
	}

	@Override
	public int nprocs() {
		return nprocs;
	}

	@Override
	public boolean topology(int i, int j) {
		return topology.valid(i, j);
	}

	@Override
	public void send(int dest, Serializable data) {
		if (!topology(rank, dest)) {
			throw new Error("Topology violation! " + rank + " cannot send to " + dest);
		}
		try {
			ObjectOutputStream o = new ObjectOutputStream(nodes[dest].os);
			o.writeObject(data);
			o.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends Serializable> T recv(int source, Class<T> type) {
		if (!topology(source, rank)) {
			throw new Error("Topology violation! " + rank + " cannot recv from " + source);
		}
		try {
			ObjectInputStream i = new ObjectInputStream(nodes[source].is);
			Object obj = i.readObject();
			if (type.isInstance(obj)) {
				//lol type safety
				return (T) obj;
			} else {
				throw new ClassNotFoundException("Object received was not of type " + type.getName());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	@Override
	public <T extends Serializable> T one2all_broadcast(int source, T data, Class<T> type) {
		if (rank() == source) {
			for (int i = 0; i < nprocs(); i++) {
				if (i != rank()) {
					send(i, data);
				}
			}
		} else {
			return recv(source, type);
		}
		return data;
	}

	@Override
	public <T extends Serializable> List<T> all2one_collect(int dest, T data, Class<T> type) {
		if (rank() != dest) {
			send(dest, data);
		} else {
			List<T> list = new ArrayList<T>();
			for (int i = 0; i < nprocs(); i++) {
				if (i != rank()) {
					list.add(recv(i, type));
				} else {
					list.add(data);
				}
			}
			return list;
		}
		return null;
	}

	@Override
	public <T extends Serializable> List<T> all2all_broadcast(T data, Class<T> type) {
		for (int i = 0; i < nprocs(); i++) {
			if (i != rank()) {
				send(i, data);
			}
		}
		List<T> list = new ArrayList<T>();
		for (int i = 0; i < nprocs(); i++) {
			if (i != rank()) {
				list.add(recv(i, type));
			} else {
				list.add(data);
			}
		}
		return list;
	}
	@Override
	public void close() {
		for (NodeSocket node : nodes) {
			node.close();
		}
		nodes = null;
	}

}