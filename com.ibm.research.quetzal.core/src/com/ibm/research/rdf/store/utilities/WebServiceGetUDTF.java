package com.ibm.research.rdf.store.utilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.WritableConstantStringObjectInspector;
import org.apache.hadoop.io.Text;

import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.Pair;

public class WebServiceGetUDTF extends GenericUDTF implements WebServiceInterface {

	private String xpathForRows = null;
	private NamespaceResolver resolver;
	private String queryText = null;

	private enum httpMethod {
		GET, POST
	};

	private httpMethod method;
	List<String> inputColumns;
	private List<Pair<String, Pair<String, String>>> xPathForColumns;

	private Map<String, Integer> outputColumnNames;
	private int indexOfInput = -1;

	@Override
	public void close() throws HiveException {
		// TODO Auto-generated method stub

	}

	@Override
	public StructObjectInspector initialize(ObjectInspector[] parameters) throws UDFArgumentException {
		List<ObjectInspector> foi = new ArrayList<ObjectInspector>();
		inputColumns = new LinkedList<String>();
		xPathForColumns = new LinkedList<Pair<String, Pair<String, String>>>();
		outputColumnNames = new HashMap<String, Integer>();

		int i = 0;
		int k = 0;
		int numWritableConstants = 0;
		while (i < parameters.length) {
			PrimitiveObjectInspector param = (PrimitiveObjectInspector) parameters[i];

			if (param instanceof WritableConstantStringObjectInspector) {
				WritableConstantStringObjectInspector wc = (WritableConstantStringObjectInspector) param;
				switch (k) {
				case 0:
					String str = wc.getWritableConstantValue().toString();
					handleOutputTypeSpecification(foi, str);
					break;
				case 1:
					str = wc.getWritableConstantValue().toString();
					StringTokenizer tokenizer = new StringTokenizer(str, ",");
					while (tokenizer.hasMoreTokens()) {
						inputColumns.add(tokenizer.nextToken());
					}
					break;
				case 2:
					queryText = wc.getWritableConstantValue().toString();
					break;
				case 3:
					str = wc.getWritableConstantValue().toString();
					if (str.equals("GET")) {
						method = httpMethod.GET;
					} else {
						method = httpMethod.POST;
					}
					break;
				case 4:
					resolver = createNamespaces(wc.getWritableConstantValue().toString());
					break;
				case 5:
					xpathForRows = wc.getWritableConstantValue().toString();
					break;
				default:
					addColXpathTuple(parameters, i);
					i += 2;
				}
				k++;
				numWritableConstants = i;
			}
			i++;
		}
		indexOfInput = numWritableConstants + 1; // Input starts where writable
													// constants end

		return ObjectInspectorFactory
				.getStandardStructObjectInspector(new LinkedList<String>(outputColumnNames.keySet()), foi);

	}

	@Override
	public void process(Object[] arg0) throws HiveException {
		String url = PrimitiveObjectInspectorFactory.javaStringObjectInspector
				.getPrimitiveJavaObject(arg0[indexOfInput]);
		try {

			List<Object[]> result = null;
			InputStream stream = getResponseAsStream(url, arg0);
			result = parseResponse(stream, resolver, xpathForRows, xPathForColumns, false);
			for (Object[] record : result) {
				// KAVITHA: This is most annoying but we have a situation where sometimes, data from input needs
				// to be passed along, but we don't really get it back from the web service.  So search parsed
				// response table to see if we have nulls (i.e., things we didnt get back from the web service but
				// we are still expected to output).			
				int k = 0;
				for (k = 0; k < record.length; k++) {
					if (record[k] == null)
						break;
					k++;
				}
				if (k == record.length) {
					forward(record); 
				} else {

					int actualInputLength = arg0.length - indexOfInput - 1;
					System.out.println("actualinputlength" + actualInputLength);
					System.out.println("indexof input:" + indexOfInput);
					System.out.println("arg0.length:" + arg0.length);					
					assert k + actualInputLength < record.length;		
					
					int l = indexOfInput + 1;
					for (int j = k; j < record.length; j++) {
						Object obj = arg0[l];
						if (obj instanceof String) {
							record[j] = new Text((String) obj);
						} else if (obj instanceof Short) {
							record[j] = new ShortWritable((Short) obj);
						}
						l++;
					}
					
					for (int i = 0; i < record.length; i++) {
						System.out.println("publishing row:" + record[i].toString());
						System.out.println("record cell class:" + record[i].getClass());
					}
					forward(record);
				}
			}
			stream.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private String addURLParams(String url, Object[] arg0) {
		
		String replacedUrl = url.replaceAll("\"", "");
		// KAVITHA: an assumption here is that all the input column names are passed in the same exact order
		// as they are called in the service URL.  Its unfortunate but we have no real sense of what input columns are in what order
		// so this assumption has to hold for now.
		
		for (int i = 1; i < inputColumns.size(); i++) {
			String regex = "\\|\\|[^|]*\\|\\|";
			replacedUrl = replacedUrl.replaceFirst(regex, arg0[indexOfInput + i].toString());
		}
		System.out.println("replaced url:" + replacedUrl);
		return replacedUrl;
	}

	public InputStream getResponseAsStream(String url, Object[] arg0) {
		InputStream stream = null;

		try {
			if (url.startsWith("file:")) {
				URL urlConn = new URL(url);
				stream = urlConn.openStream();
				return stream;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		HttpClient client = new HttpClient();

		try {

			if (method == httpMethod.GET) {
				if (!queryText.isEmpty()) {
					url = url + "?" + URLEncoder.encode(queryText, "UTF-8");
				} else {
					url = addURLParams(url, arg0);
				}

				GetMethod getMethod = new GetMethod(url);
				client.executeMethod(getMethod);
				stream = getMethod.getResponseBodyAsStream();
			} else {
				PostMethod postMethod = new PostMethod(url);
				for (int i = indexOfInput + 1; i < inputColumns.size(); i++) {
					postMethod.setParameter(inputColumns.get(i),
							PrimitiveObjectInspectorFactory.writableStringObjectInspector
									.getPrimitiveJavaObject(arg0[i]));
				}
				client.executeMethod(postMethod);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return stream;
	}

	@Override
	public Map<String, Integer> getOutputColumnNames() {
		// TODO Auto-generated method stub
		return outputColumnNames;
	}

	@Override
	public List<Pair<String, Pair<String, String>>> getXpathForColumns() {
		// TODO Auto-generated method stub
		return xPathForColumns;
	}

	public static void main(String[] args) throws Exception {
		// test1(args);
		test2();
	}

	public static void test2() {
		Object[] argsToProcess = new Object[2];
		String url = "http://localhost:8083/getDrugTransporters?drugName=\"||QS0.drug||\"";
		argsToProcess[0] = PrimitiveObjectInspectorFactory.javaStringObjectInspector
				.create("http://localhost:8083/getDrugTransporters?drugName=\"||QS0.drug||\"");
		argsToProcess[1] = PrimitiveObjectInspectorFactory.javaStringObjectInspector.create("Vasopressin");
		WebServiceGetUDTF udtf = new WebServiceGetUDTF();
		udtf.indexOfInput = 0;
		udtf.inputColumns = new LinkedList<String>();
		udtf.inputColumns.add("url");
		udtf.inputColumns.add("drug");

		udtf.addURLParams(url, argsToProcess);
	}

	public static void test1(String[] args) throws Exception, FileNotFoundException {
		Object[] argsToProcess = new Object[1];
		argsToProcess[0] = PrimitiveObjectInspectorFactory.javaStringObjectInspector
				.create("http://localhost:8081/getDrugBank");

		argsToProcess[0] = PrimitiveObjectInspectorFactory.javaStringObjectInspector
				.create("http://localhost:8081/getDrugBank");
				// argsToProcess[0] =
				// PrimitiveObjectInspectorFactory.javaStringObjectInspector.create("file:///tmp/foo");

		// ObjectInspector[] inputParams = createInput();

		WebServiceGetUDTF udtf = new WebServiceGetUDTF();
		// udtf.initialize(inputParams);
		// udtf.process(argsToProcess);
		udtf.outputColumnNames = new HashMapFactory().make();
		udtf.outputColumnNames.put("drug", new Integer(0));
		udtf.outputColumnNames.put("drug_typ", new Integer(1));

		udtf.outputColumnNames.put("id", new Integer(2));
		udtf.outputColumnNames.put("id_typ", new Integer(3));

		udtf.outputColumnNames.put("action", new Integer(4));
		udtf.outputColumnNames.put("action_typ", new Integer(5));

		Map h = new HashMap<String, String>();
		h.put("x", "http://www.drugbank.ca");
		h.put("xs", "http://www.w3.org/2001/XMLSchema");

		NamespaceResolver namespace = new NamespaceResolver(h);
		List<Pair<String, Pair<String, String>>> xPathForEachColumn = new LinkedList<Pair<String, Pair<String, String>>>();
		Pair<String, String> p = Pair.make("./x:drug", "xs:string");
		Pair<String, Pair<String, String>> pk = Pair.make("drug", p);
		xPathForEachColumn.add(pk);
		p = Pair.make("./x:id", "xs:string");
		pk = Pair.make("id", p);
		xPathForEachColumn.add(pk);
		p = Pair.make("./x:action", "xs:string");
		pk = Pair.make("action", p);
		xPathForEachColumn.add(pk);
		udtf.parseResponse(new FileInputStream(new File(args[0])), namespace, "//x:row", xPathForEachColumn, false);
	}

	public static ObjectInspector[] createInput() {
		ObjectInspector[] inputParams = new ObjectInspector[15];
		ObjectInspector sc = PrimitiveObjectInspectorFactory.javaStringObjectInspector;
		/*
		 * inputParams[0] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("url")); inputParams[1] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("")); inputParams[2] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("GET")); inputParams[3] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("xs=http://www.w3.org/2001/XMLSchema")); inputParams[4] =
		 * sc; sc = (ObjectInspector) PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("//row")); inputParams[5] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("drug")); inputParams[6] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("./drug")); inputParams[7] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("<string>")); inputParams[8] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("id")); inputParams[9] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("./id")); inputParams[10] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("<string>")); inputParams[11] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("action")); inputParams[12] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("./action")); inputParams[13] = sc; sc = (ObjectInspector)
		 * PrimitiveObjectInspectorFactory.
		 * getPrimitiveWritableConstantObjectInspector(PrimitiveCategory.STRING,
		 * new Text("<string>")); inputParams[14] = sc;
		 */
		return inputParams;
	}

}
