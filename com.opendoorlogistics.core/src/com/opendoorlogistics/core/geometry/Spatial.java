/*******************************************************************************
 * Copyright (c) 2014 Open Door Logistics (www.opendoorlogistics.com)
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v3
 * which accompanies this distribution, and is available at http://www.gnu.org/licenses/lgpl.txt
 ******************************************************************************/
package com.opendoorlogistics.core.geometry;

import java.io.File;
import java.util.Map;

import org.geotools.referencing.CRS;
import org.geotools.referencing.ReferencingFactoryFinder;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

import com.opendoorlogistics.api.tables.ODLColumnType;
import com.opendoorlogistics.api.tables.ODLDatastore;
import com.opendoorlogistics.api.tables.ODLDatastoreAlterable;
import com.opendoorlogistics.api.tables.ODLTableAlterable;
import com.opendoorlogistics.api.tables.ODLTableReadOnly;
import com.opendoorlogistics.core.tables.memory.ODLDatastoreImpl;
import com.opendoorlogistics.core.tables.utils.TableUtils;
import com.opendoorlogistics.core.utils.SimpleSoftReferenceMap;
import com.vividsolutions.jts.geom.Geometry;

/**
 * Performs one off initialisation of geotools components which must be 
 * called before any used and stores any singleton components.
 * @author Phil
 *
 */
public final class Spatial {
	private static boolean init=false;
	private static CoordinateReferenceSystem wgs84crs;
	private static CRSAuthorityFactory crsFac;
	private static final SimpleSoftReferenceMap<ShapefileLink,GeomWithCache> shapefileLinkCache = new SimpleSoftReferenceMap<>(100);
	private static final SimpleSoftReferenceMap<File, ODLDatastore<? extends ODLTableReadOnly>> shapefileLookupCache = new SimpleSoftReferenceMap<>();
	
	public static synchronized void initSpatial(){
		if(!init){
			// ensure geotools uses longitude, latitude order, not latitude, longitude, in the entire application
			System.setProperty("org.geotools.referencing.forceXY", "true");
			crsFac = ReferencingFactoryFinder.getCRSAuthorityFactory("EPSG", null);		
			try {
				wgs84crs = crsFac.createCoordinateReferenceSystem("4326");				
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	public static CoordinateReferenceSystem wgs84(){
		initSpatial();
		return wgs84crs;
	}
	
	/**
	 * Create math transform to go from WGS84 to the input EPSG SRID system
	 * @param espg_srid
	 * @return
	 */
	public static MathTransform fromWGS84(String espg_srid){
		try {
			CoordinateReferenceSystem crs = crsFac.createCoordinateReferenceSystem(espg_srid);
			return CRS.findMathTransform(wgs84crs,crs ,true);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Get transform to turn other coord system into WGS84
	 * @param crs
	 * @return
	 */
	public static MathTransform toWgs84(CoordinateReferenceSystem crs){
		initSpatial();
		try {
			return CRS.findMathTransform(crs, wgs84crs,true);
		//	return new DefaultCoordinateOperationFactory().createOperation(wgs84crs, crs);			
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	

	static synchronized GeomWithCache loadLink(ShapefileLink link){
		initSpatial();
		GeomWithCache ret = shapefileLinkCache.get(link);
		if(ret==null){
			// parse and cache the entire file as we would normally use many objects in the same file
			for(Map.Entry<ShapefileLink, Geometry> entry : ImportShapefile.importShapefile(new File(link.getFile()),false, null).entrySet()){
				if(entry.getValue()!=null){
					ShapefileLink importedLink = entry.getKey();					
					GeomWithCache gwc =  new GeomWithCache(entry.getValue());
					if(link.equals(importedLink)){
						ret = gwc;
					}
					
					cacheLink(importedLink, gwc);					
				}
			}
		}
		return ret;
	}

	private synchronized static void cacheLink(ShapefileLink link, GeomWithCache gwc) {
		if(shapefileLinkCache.get(link)==null){
			shapefileLinkCache.put(link, gwc);
		}
	}
	
	/**
	 * Looks up a value in the shapefile and returns the geometry for it
	 * @param filename Shapefile filename.
	 * @param searchvalue Value to search for.
	 * @param type Typename in the file to search within.
	 * @param searchfield Field within the type to search within.
	 * @return
	 */
	static public synchronized ODLGeomImpl lookupshapefile(String filename,String searchvalue, String type, String searchfield ){
		Spatial.initSpatial();
		
		// try getting the shapefile from cache first
		boolean ok = true;
		ODLDatastore<? extends ODLTableReadOnly> ds=null;
		File file = new File(filename);
		ds = shapefileLookupCache.get(file);
		if(ds==null){
			ODLDatastoreAlterable<ODLTableAlterable> alterableDs =  ODLDatastoreImpl.alterableFactory.create();
			ImportShapefile.importShapefile(file,false,alterableDs);			
			ds = alterableDs;
			if(alterableDs.getTableCount()>0){
				shapefileLookupCache.put(file, ds);
			}
		}
		
		// find table
		ODLTableReadOnly table= TableUtils.findTable(ds, type);
		ok = table!=null;

		// get geometry field
		int geomIndx = -1;
		if(ok){
			geomIndx = TableUtils.findColumnIndx(table, ODLColumnType.GEOM);
			ok = geomIndx!=-1;
		}
		
		// find the search column index
		int searchIndx=-1;
		if(ok){
			searchIndx = TableUtils.findColumnIndx(table, searchfield);
			ok = searchIndx!=-1;			
		}
		
		// find the value
		if(ok){
			long[] list = table.find(searchIndx, searchvalue);
			if(list.length>0){
				return (ODLGeomImpl) table.getValueById(list[0], geomIndx);
			}
		}

		return null;
	}
	
	static{
		initSpatial();
	}
}
