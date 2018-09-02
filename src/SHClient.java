import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import com.mbed.coap.client.CoapClient;
import com.mbed.coap.client.CoapClientBuilder;
import com.mbed.coap.packet.CoapPacket;
import com.mbed.coap.packet.MediaTypes;

public class SHClient {

	public static final String PROTOCOL = "http"; // 'coap' or 'http'
	public static final int NUMBER_OF_OPS = 10;
	public static final double PERCENTAGE_OF_READ_OPS = 0.6;
	public static final int NUMBER_OF_CLIENTS = 75;

	public static final String SH_DOMAIN = "localhost";
	public static final int SH_COAP_PORT = 5683;
	public static final int SH_HTTP_PORT = 8080;

	public static final String HTTP_KEYSTORE_FILEPATH = "clientkeystore.p12";
	public static final String HTTP_TRUSTSTORE_FILEPATH = "clienttruststore.p12";
	public static final String HTTP_KEYSTORE_FORMAT = "PKCS12";
	public static final String HTTP_TRUSTSTORE_FORMAT = "PKCS12";
	public static final String HTTP_KEYSTORE_PW = "sparkmeup";
	public static final String HTTP_TRUSTSTORE_PW = "sparkmeup";
	public static final String HTTP_CERT_FORMAT = "SunX509";
	public static final String HTTP_TLS_VERSION = "TLSv1.2";

	public static void main(String[] args) throws Exception {

		Thread[] clientThreads = new Thread[NUMBER_OF_CLIENTS];
		Queue<Long> timeQueue = new ConcurrentLinkedQueue<Long>();

		for (int i = 0; i < NUMBER_OF_CLIENTS; i++) {

			clientThreads[i] = new Thread() {
				@Override
				public void run() {

					Random r = new Random();
					try {
						if (PROTOCOL.equals("coap")) {
							InetSocketAddress isock = new InetSocketAddress(SH_DOMAIN, SH_COAP_PORT);
							CoapClient client = CoapClientBuilder.newBuilder(isock).build();

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

							// read ops
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
						} else if (PROTOCOL.equals("http")) {

							Security.addProvider(new BouncyCastleProvider());

							File keystoreFile = new File(HTTP_KEYSTORE_FILEPATH);
							File truststoreFile = new File(HTTP_TRUSTSTORE_FILEPATH);

							// fetch p12 keystore from assets and load it up (as a x509 cert)
							InputStream is = new FileInputStream(keystoreFile);
							KeyStore keyStore = KeyStore.getInstance(HTTP_KEYSTORE_FORMAT);
							keyStore.load(is, HTTP_KEYSTORE_PW.toCharArray());
							KeyManagerFactory kmf = KeyManagerFactory.getInstance(HTTP_CERT_FORMAT);
							kmf.init(keyStore, HTTP_KEYSTORE_PW.toCharArray());
							KeyManager[] keyManagers = kmf.getKeyManagers();

							// load truststore
							is = new FileInputStream(truststoreFile);
							KeyStore trustStore = KeyStore.getInstance(HTTP_TRUSTSTORE_FORMAT);
							trustStore.load(is, HTTP_TRUSTSTORE_PW.toCharArray());
							TrustManagerFactory tmf = TrustManagerFactory.getInstance(HTTP_CERT_FORMAT);
							tmf.init(trustStore);
							TrustManager[] trustManagers = tmf.getTrustManagers();

							// setup ssl context
							SSLContext sslContext = SSLContext.getInstance(HTTP_TLS_VERSION);
							sslContext.init(keyManagers, trustManagers, new SecureRandom());

							String smartHubUrl = "https://" + SH_DOMAIN + ":" + SH_HTTP_PORT
									+ "/api/mainchannel/contract/xcc";
							String requestMethod = "POST";
							String requestParameters = "operationId=put&operationArgs=[\"" + r.nextInt(10000) + "\",\"abc\"]";
							// write ops
							for (int j = 0; j < NUMBER_OF_OPS - (PERCENTAGE_OF_READ_OPS * NUMBER_OF_OPS); j++) {

								long startT = System.nanoTime();
								makeHTTPRequest(smartHubUrl + "/invoke", sslContext, requestMethod, requestParameters);
								long endT = System.nanoTime();
								long deltaT = endT - startT;
								timeQueue.add(deltaT);
							}

							requestParameters = "operationId=queryAll";
							// read ops
							for (int k = 0; k < PERCENTAGE_OF_READ_OPS * NUMBER_OF_OPS; k++) {

								long startT = System.nanoTime();
								makeHTTPRequest(smartHubUrl + "/query", sslContext, requestMethod, requestParameters);
								long endT = System.nanoTime();
								long deltaT = endT - startT;
								timeQueue.add(deltaT);
							}

						}
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

	private static void makeHTTPRequest(String smartHubUrl, SSLContext sslContext, String requestMethod,
			String requestParameters) {
		// perform URL request to API
		String responseContentType = null, responseBody = null;
		int responseCode = -1;
		HttpURLConnection urlConnection = null;

		try {
			// setup https connection to smart hub
			URL requestedUrl = new URL(smartHubUrl);
			urlConnection = (HttpURLConnection) requestedUrl.openConnection();
			if (urlConnection instanceof HttpsURLConnection) {
				((HttpsURLConnection) urlConnection).setHostnameVerifier(new HostnameVerifier() {
					@Override
					public boolean verify(String s, SSLSession sslSession) {
						// do not verify hostnames, we will deploy this in IP addresses....
						return true;
					}
				});
				((HttpsURLConnection) urlConnection).setSSLSocketFactory(sslContext.getSocketFactory());
			}

			urlConnection.setDoOutput(true);
			urlConnection.setUseCaches(false);

			// setup request
			urlConnection.setRequestMethod(requestMethod);
			urlConnection.setRequestProperty("Accept", "application/json");
			urlConnection.setConnectTimeout(500000);
			urlConnection.setReadTimeout(150000);

			// body parameters
			if (requestParameters != null && !requestParameters.isEmpty() && requestMethod.equals("POST")) {
				byte[] requestParametersBytes = requestParameters.getBytes("UTF-8");
				OutputStream os = urlConnection.getOutputStream();
				os.write(requestParametersBytes);
				os.close();
			}
			urlConnection.connect();

			System.out.println(requestMethod + " " + smartHubUrl + " " + requestParameters);
			responseCode = urlConnection.getResponseCode();
			responseContentType = urlConnection.getContentType();
			
			BufferedInputStream bis = new BufferedInputStream(urlConnection.getInputStream());
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			int result2 = bis.read();
			while (result2 != -1) {
				buf.write((byte) result2);
				result2 = bis.read();
			}
			responseBody = buf.toString();
			System.out.println(
					"Rsp: HTTP " + responseCode + " Content-Type: " + responseContentType + " Content:" + responseBody);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if (urlConnection != null) {
				urlConnection.disconnect();
			}
		}
	}

}
