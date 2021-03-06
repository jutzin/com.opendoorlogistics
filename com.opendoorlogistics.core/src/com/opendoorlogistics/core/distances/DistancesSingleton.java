/*******************************************************************************
 * Copyright (c) 2014 Open Door Logistics (www.opendoorlogistics.com)
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at http://www.gnu.org/licenses/lgpl.txt
 ******************************************************************************/
package com.opendoorlogistics.core.distances;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.graphhopper.util.shapes.GHPoint;
import com.opendoorlogistics.api.components.PredefinedTags;
import com.opendoorlogistics.api.components.ProcessingApi;
import com.opendoorlogistics.api.distances.DistancesConfiguration;
import com.opendoorlogistics.api.distances.DistancesConfiguration.CalculationMethod;
import com.opendoorlogistics.api.distances.DistancesOutputConfiguration;
import com.opendoorlogistics.api.distances.ODLCostMatrix;
import com.opendoorlogistics.api.geometry.LatLong;
import com.opendoorlogistics.api.geometry.ODLGeom;
import com.opendoorlogistics.api.tables.ODLColumnType;
import com.opendoorlogistics.api.tables.ODLTableDefinition;
import com.opendoorlogistics.api.tables.ODLTableReadOnly;
import com.opendoorlogistics.api.ui.Disposable;
import com.opendoorlogistics.core.cache.ApplicationCache;
import com.opendoorlogistics.core.cache.RecentlyUsedCache;
import com.opendoorlogistics.core.distances.graphhopper.CHMatrixGeneration;
import com.opendoorlogistics.core.distances.graphhopper.MatrixResult;
import com.opendoorlogistics.core.gis.GeoUtils;
import com.opendoorlogistics.core.gis.map.data.LatLongImpl;
import com.opendoorlogistics.core.scripts.execution.dependencyinjection.ProcessingApiDecorator;
import com.opendoorlogistics.core.scripts.wizard.TagUtils;
import com.opendoorlogistics.core.tables.ColumnValueProcessor;
import com.opendoorlogistics.core.utils.iterators.IteratorUtils;
import com.opendoorlogistics.core.utils.strings.StandardisedStringTreeMap;
import com.opendoorlogistics.core.utils.strings.Strings;


public final class DistancesSingleton implements Disposable{
	//private final RecentlyUsedCache recentMatrixCache = new RecentlyUsedCache(128 * 1024 * 1024);
	//private final RecentlyUsedCache recentGeomCache = new RecentlyUsedCache(64 * 1024 * 1024);
	private CHMatrixGeneration lastCHGraph;
	
	private DistancesSingleton() {
	}

	private static final DistancesSingleton singleton = new DistancesSingleton();

	private static class InputTableAccessor {
		private final int locCol;
		private final int latCol;
		private final int lngCol;
		private final ODLTableReadOnly table;

		InputTableAccessor(ODLTableReadOnly table) {
			locCol = findTag(PredefinedTags.LOCATION_KEY, table);
			latCol = findTag(PredefinedTags.LATITUDE, table);
			lngCol = findTag(PredefinedTags.LONGITUDE, table);
			this.table = table;
		}

		private static int findTag(String tag, ODLTableDefinition table) {
			int col = TagUtils.findTag(tag, table);
			if (col == -1) {
				throw new RuntimeException("Distances input table does not contain tag for: " + tag);
			}
			return col;
		}

		double getLatitude(int row) {
			return (Double) getValueAt(row, latCol, ODLColumnType.DOUBLE);
		}

		double getLongitude(int row) {
			return (Double) getValueAt(row, lngCol, ODLColumnType.DOUBLE);
		}

		String getLoc(int row) {
			return (String) getValueAt(row, locCol, ODLColumnType.STRING);
		}

		private Object getValueAt(int row, int col, ODLColumnType type) {
			Object ret = table.getValueAt(row, col);
			if (ret == null) {
				throw new RuntimeException("Distances input table has a null value: " + getElemDescription(row, col));
			}

			ret = ColumnValueProcessor.convertToMe(type,ret);
			if (ret == null) {
				throw new RuntimeException("Distances input table has a value which cannot be converted to correct type: " + getElemDescription(row, col) + ", type=" + Strings.convertEnumToDisplayFriendly(type.toString()));
			}

			return ret;
		}

		private String getElemDescription(int row, int col) {
			return "table=" + table.getName() + ", " + "row=" + (row + 1) + ", column=" + table.getColumnName(col);
		}

	}

	private synchronized ODLCostMatrix calculateGraphhopper(DistancesConfiguration request, StandardisedStringTreeMap<LatLong> points, final ProcessingApi processingApi) {
		initGraphhopperGraph(request, processingApi);
		
		final StringBuilder statusMessage = new StringBuilder();
		statusMessage.append ("Loaded the graph " + new File(request.getGraphhopperConfig().getGraphDirectory()).getAbsolutePath());				
		statusMessage.append(System.lineSeparator() + "Calculating " + points.size() + "x" + points.size() + " matrix using Graphhopper road network distances.");
		processingApi.postStatusMessage(statusMessage.toString());

		// check for user cancellation
		if(processingApi!=null && processingApi.isCancelled()){
			return null;
		}

		// convert input to an array of graphhopper points
		int n = points.size();
		int i =0;
		List<Map.Entry<String, LatLong>> list = IteratorUtils.toList(points.entrySet());
		GHPoint []ghPoints = new GHPoint[n];
		for(Map.Entry<String, LatLong> entry:list){
			ghPoints[i++] = new GHPoint(entry.getValue().getLatitude(), entry.getValue().getLongitude());
		}
		
		// calculate the matrix 
		MatrixResult result = lastCHGraph.calculateMatrix(ghPoints, new ProcessingApiDecorator(processingApi) {
				
			@Override
			public void postStatusMessage(String s) {
				processingApi.postStatusMessage(statusMessage.toString() + System.lineSeparator() + s);
			}
		});
		if(processingApi!=null && processingApi.isCancelled()){
			return null;
		}

		// convert result to the output data structure
		ODLCostMatrixImpl output = createEmptyMatrix(list);
		for (int ifrom = 0; ifrom < n; ifrom++) {
			for (int ito = 0; ito < n; ito++) {
				double timeSeconds = result.getTimeMilliseconds(ifrom, ito) * 0.001;
				timeSeconds *= request.getGraphhopperConfig().getTimeMultiplier();
				if(!result.isInfinite(ifrom, ito)){
					setOutputValues(ifrom, ito, result.getDistanceMetres(ifrom, ito), timeSeconds, request.getOutputConfig(), output);					
				}else{
					for(int k=0; k<3 ;k++){
						output.set(Double.POSITIVE_INFINITY, ifrom, ito, k);						
					}
				}
			}
		}
		
		return output;
	}

	/**
	 * @param request
	 * @param processingApi
	 */
	private synchronized void initGraphhopperGraph(DistancesConfiguration request, final ProcessingApi processingApi) {
		String dir = request.getGraphhopperConfig().getGraphDirectory();
		File current = new File(dir);

		if(processingApi!=null){
			processingApi.postStatusMessage("Loading the road network graph: " + current.getAbsolutePath());			
		}
		
		// check current file is valid
		if(!current.exists() || !current.isDirectory()){
			throw new RuntimeException("Invalid Graphhopper directory: " + dir);
		}
		
		// check if last one has an invalid file
		if(lastCHGraph!=null){
			File file = new File(lastCHGraph.getGraphFolder());
			if(!file.exists() || !file.isDirectory()){
				lastCHGraph.dispose();
				lastCHGraph = null;
			}
		}
		
		// check if using different file
		if(lastCHGraph!=null){
			File lastFile = new File(lastCHGraph.getGraphFolder());
			if(!lastFile.equals(current)){
				lastCHGraph.dispose();
				lastCHGraph = null;				
			}		
		}
		
		// load the graph if needed
		if(lastCHGraph==null){
			lastCHGraph = new CHMatrixGeneration(dir);
		}
	}

	private ODLCostMatrix calculateGreatCircle(DistancesConfiguration request, StandardisedStringTreeMap<LatLong> points, ProcessingApi processingApi) {
		processingApi.postStatusMessage("Calculating " + points.size() + "x" + (points.size() + " matrix using great circle distance (i.e. straight line)"));
		
		List<Map.Entry<String, LatLong>> list = IteratorUtils.toList(points.entrySet());
		ODLCostMatrixImpl output = createEmptyMatrix(list);

		int n = list.size();
		for (int ifrom = 0; ifrom < n; ifrom++) {
			Map.Entry<String, LatLong> from = list.get(ifrom);
			for (int ito = 0; ito < n; ito++) {
				Map.Entry<String, LatLong> to = list.get(ito);

				double distanceMetres = GeoUtils.greatCircleApprox(from.getValue(), to.getValue());

				distanceMetres *= request.getGreatCircleConfig().getDistanceMultiplier();

				double timeSecs = distanceMetres / request.getGreatCircleConfig().getSpeedMetresPerSec();

				// output cost and time
				setOutputValues(ifrom, ito, distanceMetres, timeSecs, request.getOutputConfig(), output);

				// check for user cancellation
				if (processingApi.isCancelled()) {
					break;
				}

			}
		}

		return output;
	}

	private void setOutputValues(int ifrom, int ito, double distanceMetres, double timeSecs, DistancesOutputConfiguration outputConfig, ODLCostMatrixImpl output) {
		double value = processOutput(distanceMetres, timeSecs, outputConfig);
		output.set(value, ifrom, ito, 0);
		output.set(processedDistance(distanceMetres, outputConfig), ifrom, ito, 1);
		output.set(processedTime(timeSecs, outputConfig), ifrom, ito, 2);
	}

	private ODLCostMatrixImpl createEmptyMatrix(List<Map.Entry<String, LatLong>> list) {
		ArrayList<String> idList = new ArrayList<>();
		for (Map.Entry<String, LatLong> entry : list) {
			idList.add(entry.getKey());
		}
		ODLCostMatrixImpl output = new ODLCostMatrixImpl(idList, new String[] { PredefinedTags.TRAVEL_COST, PredefinedTags.DISTANCE, PredefinedTags.TIME });
		return output;
	}

	public static DistancesSingleton singleton() {
		return singleton;
	}

	private static class RouteGeomCacheKey{
		final private DistancesConfiguration request;
		final private LatLong from;
		final private LatLong to;
		
		RouteGeomCacheKey(DistancesConfiguration request, LatLong from, LatLong to) {
			this.request = request.deepCopy();
			this.from = new LatLongImpl(from);
			this.to = new LatLongImpl(to);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((from == null) ? 0 : from.hashCode());
			result = prime * result + ((request == null) ? 0 : request.hashCode());
			result = prime * result + ((to == null) ? 0 : to.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			RouteGeomCacheKey other = (RouteGeomCacheKey) obj;
			if (from == null) {
				if (other.from != null)
					return false;
			} else if (!from.equals(other.from))
				return false;
			if (request == null) {
				if (other.request != null)
					return false;
			} else if (!request.equals(other.request))
				return false;
			if (to == null) {
				if (other.to != null)
					return false;
			} else if (!to.equals(other.to))
				return false;
			return true;
		}
		
		
	}
	
	private static class MatrixCacheKey {
		final private DistancesConfiguration request;
		final private StandardisedStringTreeMap<LatLong> points;
		final private int hashcode;

		private MatrixCacheKey(DistancesConfiguration request, StandardisedStringTreeMap<LatLong> points) {
			this.request = request.deepCopy();
			this.points = points;

			final int prime = 31;
			int result = 1;
			result = prime * result + ((points == null) ? 0 : points.hashCode());
			result = prime * result + ((request == null) ? 0 : request.hashCode());
			hashcode = result;
		}

		@Override
		public int hashCode() {
			return hashcode;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			MatrixCacheKey other = (MatrixCacheKey) obj;
			if (points == null) {
				if (other.points != null)
					return false;
			} else if (!points.equals(other.points))
				return false;
			if (request == null) {
				if (other.request != null)
					return false;
			} else if (!request.equals(other.request))
				return false;
			return true;
		}

	}

	public synchronized ODLGeom calculateRouteGeom(DistancesConfiguration request, LatLong from, LatLong to, ProcessingApi processingApi){
		if(request.getMethod() == CalculationMethod.GREAT_CIRCLE){
			ODLGeom geom = processingApi.getApi().geometry().createLineGeometry(from, to);
			return geom;
		}
		
		if(request.getMethod()!=CalculationMethod.ROAD_NETWORK){
			throw new IllegalArgumentException("Can only calculate route geometry if the distance calculation is set to road network.");
		}
		
		RouteGeomCacheKey key = new RouteGeomCacheKey(request, from, to);
		RecentlyUsedCache cache = ApplicationCache.singleton().get(ApplicationCache.ROUTE_GEOMETRY_CACHE);
		ODLGeom ret = (ODLGeom) cache.get(key);
		if (ret != null) {
			return ret;
		}

		initGraphhopperGraph(request, processingApi);
		
		ret = lastCHGraph.calculateRouteGeom(from, to);
		if(ret!=null){
			int estimatedSize = 40 * ret.getPointsCount();
			cache.put(key, ret,estimatedSize);
		}
		
		// give a straight line if all else fails
		if(ret==null){
			ret = processingApi.getApi().geometry().createLineGeometry(from, to);			
		}
		return ret;
	}

	public synchronized ODLCostMatrix calculate(DistancesConfiguration request, ProcessingApi processingApi, ODLTableReadOnly... tables) {
		
		// get all locations
		StandardisedStringTreeMap<LatLong> points = getPoints(tables);

		MatrixCacheKey key = new MatrixCacheKey(request, points);
		RecentlyUsedCache cache = ApplicationCache.singleton().get(ApplicationCache.DISTANCE_MATRIX_CACHE);
		ODLCostMatrix ret = (ODLCostMatrix) cache.get(key);
		if (ret != null) {
			return ret;
		}

		switch (request.getMethod()) {
		case GREAT_CIRCLE:
			ret = calculateGreatCircle(request, points, processingApi);
			break;

		case ROAD_NETWORK:
			ret = calculateGraphhopper(request, points, processingApi);
			break;
			
		default:
			throw new UnsupportedOperationException(request.getMethod().toString() + " is unsupported.");
		}

		if(ret.getSizeInBytes() < Integer.MAX_VALUE){
			cache.put(key, ret, (int)ret.getSizeInBytes());			
		}

		return ret;
	}

	private StandardisedStringTreeMap<LatLong> getPoints(ODLTableReadOnly... tables) {
		StandardisedStringTreeMap<LatLong> points = new StandardisedStringTreeMap<>();
		for (ODLTableReadOnly table : tables) {
			InputTableAccessor accessor = new InputTableAccessor(table);
			int nr = table.getRowCount();
			for (int row = 0; row < nr; row++) {
				LatLongImpl ll = new LatLongImpl(accessor.getLatitude(row), accessor.getLongitude(row));
				String id = accessor.getLoc(row);
				if (points.get(id) != null && points.get(id).equals(ll) == false) {
					throw new RuntimeException("Location id defined twice with different latitude/longitude pairs: " + id);
				}
				points.put(id, ll);
			}
		}
		return points;
	}

	private double processOutput(double distanceMetres, double timeSeconds, DistancesOutputConfiguration config) {
		// get distance in correct units
		double distance = processedDistance(distanceMetres, config);

		// get time in correct units
		double time = processedTime(timeSeconds, config);

		switch (config.getOutputType()) {

		case DISTANCE:
			return distance;

		case TIME:
			return time;

		case SUMMED:
			return config.getTimeWeighting() * time + config.getDistanceWeighting() * distance;

		default:
			throw new UnsupportedOperationException();
		}
	}

	private double processedTime(double timeSeconds, DistancesOutputConfiguration config) {
		double time = 0;
		switch (config.getOutputTimeUnit()) {
		case MILLISECONDS:
			time = timeSeconds * 1000;
			break;
			
		case SECONDS:
			time = timeSeconds;
			break;

		case MINUTES:
			time = timeSeconds * (1.0 / 60.0);
			break;

		case HOURS:
			time = timeSeconds * (1.0 / (60.0 * 60.0));
			break;

		default:
			throw new UnsupportedOperationException();
		}
		return time;
	}

	private double processedDistance(double distanceMetres, DistancesOutputConfiguration config) {
		double distance = 0;
		switch (config.getOutputDistanceUnit()) {
		case METRES:
			distance = distanceMetres;
			break;

		case KILOMETRES:
			distance = distanceMetres / 1000;
			break;

		case MILES:
			distance = (distanceMetres / 1000) * 0.621371;
			break;
		default:
			throw new UnsupportedOperationException();
		}
		return distance;
	}

	@Override
	public synchronized void dispose() {
		if(lastCHGraph!=null){
			lastCHGraph.dispose();
			lastCHGraph = null;
		}
	}
}
