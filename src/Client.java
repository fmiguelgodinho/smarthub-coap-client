import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mbed.coap.client.CoapClient;
import com.mbed.coap.client.CoapClientBuilder;
import com.mbed.coap.packet.CoapPacket;
import com.mbed.coap.packet.MediaTypes;

public class Client {

	public static final int NUMBER_OF_OPS = 10;
	public static final double PERCENTAGE_OF_READ_OPS = 0.6;
	public static final int NUMBER_OF_CLIENTS = 10;

	public static void main(String[] args) throws Exception {

		Thread[] clientThreads = new Thread[NUMBER_OF_CLIENTS];
		Queue<Long> timeQueue = new ConcurrentLinkedQueue<Long>();

		for (int i = 0; i < NUMBER_OF_CLIENTS; i++) {

			clientThreads[i] = new Thread() {
				@Override
				public void run() {

					try {
						InetSocketAddress isock = new InetSocketAddress("localhost", 5683);
						CoapClient client = CoapClientBuilder.newBuilder(isock).build();
						Random r = new Random();

						// write ops
						for (int j = 0; j < NUMBER_OF_OPS - (PERCENTAGE_OF_READ_OPS * NUMBER_OF_OPS); j++) {

							long startT = System.nanoTime();
							CoapPacket coapResp = null;
							// invoke operation
							coapResp = client.resource("/contract")
									.payload("channel=mainchannel&contract=xcc&operation=put&args=[\""
											+ r.nextInt(10000) + "\",\"abc\"]", MediaTypes.CT_TEXT_PLAIN)
									.sync().put();

							System.out.println("Rsp: " + coapResp.getCode() + " " + coapResp.getPayloadString());

							long endT = System.nanoTime();
							long deltaT = endT - startT;
							timeQueue.add(deltaT);
						}

						// write ops
						for (int k = 0; k < PERCENTAGE_OF_READ_OPS * NUMBER_OF_OPS; k++) {

							long startT = System.nanoTime();
							CoapPacket coapResp = null;
							// query operation
							coapResp = client.resource("/contract")
									.payload("channel=mainchannel&contract=xcc&operation=queryAll",
											MediaTypes.CT_TEXT_PLAIN)
									.sync().get();

							System.out.println("Rsp: " + coapResp.getCode() + " " + coapResp.getPayloadString());

							long endT = System.nanoTime();
							long deltaT = endT - startT;
							timeQueue.add(deltaT);
						}
						// release socket
						client.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			};
			// start the actual thread
			clientThreads[i].start();
		}

		// join/terminate the threads
		for (int i = 0; i < NUMBER_OF_CLIENTS; i++) {
			clientThreads[i].join();
		}

		long sumT = 0;
		for (long t : timeQueue) {
			sumT += t;
		}
		System.err.println("sumT: " + sumT + " timequeue size: " + timeQueue.size());
		long avgT = sumT / timeQueue.size();
		double avgTMillis = avgT / 1e6;

		System.out.println("Run completed with " + NUMBER_OF_CLIENTS + " and " + NUMBER_OF_OPS
				+ " per client. Average time per client: " + avgTMillis);

	}

}
