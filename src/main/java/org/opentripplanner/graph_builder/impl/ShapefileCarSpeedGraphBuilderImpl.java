/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.impl;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CRSAuthorityFactory;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.routing.carspeed.CarSpeedVectorField;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Graph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Geometry;

/**
 * Load car average (actual) speeds from a shapefile. Create a CarSpeedVectorField out of it and set
 * car speed of edges according to this field, statically.
 * 
 * @author laurent
 */
public class ShapefileCarSpeedGraphBuilderImpl implements GraphBuilder {

    public File shapefile;

    // TODO properly configure this
    public String carSpeedAttributeName = "Vitesse";

    public String directionAttributeName = "Sens";

    public boolean carSpeedInKmph = true;

    private static final double RADIUS_METER = 50;

    private static final Logger LOG = LoggerFactory
            .getLogger(ShapefileCarSpeedGraphBuilderImpl.class);

    /**
     * An set of ids which identifies what stages this graph builder provides (i.e. streets,
     * elevation, transit)
     */
    public List<String> provides() {
        return Collections.emptyList();
    }

    /** A list of ids of stages which must be provided before this stage */
    public List<String> getPrerequisites() {
        return Arrays.asList("streets");
    }

    @Override
    public void buildGraph(Graph graph, HashMap<Class<?>, Object> extra) {
        long start = System.currentTimeMillis();
        LOG.info("Loading car speed vector field from {}", shapefile);

        double cosLat = Math.cos(Math.toRadians(graph.getMetadata().getCenterLatitude()));
        CarSpeedVectorField field = new CarSpeedVectorField(cosLat, RADIUS_METER);

        try {

            Map<String, Serializable> map = new HashMap<>();
            map.put("url", shapefile.toURI().toURL());

            ShapefileDataStore dataStore = (ShapefileDataStore) DataStoreFinder.getDataStore(map);
            String typeName = dataStore.getTypeNames()[0];

            FeatureSource<SimpleFeatureType, SimpleFeature> source = dataStore
                    .getFeatureSource(typeName);
            FeatureCollection<SimpleFeatureType, SimpleFeature> collection = source.getFeatures();
            FeatureIterator<SimpleFeature> results = collection.features();

            CoordinateReferenceSystem sourceCRS = null;
            // The following code ensure X as LON, as OTP expect it.
            CRSAuthorityFactory factory = CRS.getAuthorityFactory(true);
            CoordinateReferenceSystem targetCRS = factory
                    .createCoordinateReferenceSystem("EPSG:4326");
            MathTransform transform = null;
            try {
                int nFeatures = 0, nSkippedNoSpeed = 0, nSkippedNoGeom = 0;
                while (results.hasNext()) {
                    SimpleFeature feature = (SimpleFeature) results.next();
                    CoordinateReferenceSystem crs = feature.getFeatureType()
                            .getGeometryDescriptor().getCoordinateReferenceSystem();
                    if (!crs.equals(sourceCRS) || transform == null) {
                        sourceCRS = crs;
                        transform = CRS.findMathTransform(sourceCRS, targetCRS, true);
                    }
                    Geometry geom = (Geometry) feature.getDefaultGeometry();
                    if (geom == null) {
                        nSkippedNoGeom++;
                        continue;
                    }
                    Geometry geoGeom = JTS.transform(geom, transform);
                    Float carSpeed = Float.parseFloat(feature.getAttribute(carSpeedAttributeName)
                            .toString());
                    if (carSpeed == null) {
                        nSkippedNoSpeed++;
                        continue;
                    }
                    Integer dir = (Integer) feature.getAttribute(directionAttributeName);
                    boolean fwd = true;
                    boolean rev = true;
                    if (dir != null) {
                        if (dir == 1)
                            rev = false;
                        if (dir == -1)
                            fwd = false;
                    }
                    if (carSpeedInKmph)
                        carSpeed /= 3.6f;
                    field.addGeometry(geoGeom, carSpeed, fwd, rev);
                    nFeatures++;
                }
                LOG.info("Loaded {} features, skipped {}: no speed, {}: no geom.", nFeatures,
                        nSkippedNoSpeed, nSkippedNoGeom);
            } finally {
                results.close();
                dataStore.dispose();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int nSet = 0, nNoSpeed = 0, nClampedSpeed = 0;
        Collection<StreetEdge> streetEdges = graph.getStreetEdges();
        int n = 0;
        for (StreetEdge e : streetEdges) {
            Float newSpeed = field.interpolateSpeedForEdge(e);
            if (newSpeed == null) {
                nNoSpeed++;
            } else {
                if (newSpeed > e.getCarSpeed() * 1.2) {
                    nClampedSpeed++;
                } else {
                    e.setCarSpeed(newSpeed);
                    nSet++;
                }
            }
            n++;
            if (n % 100000 == 0) {
                LOG.info("Processed {} / {} edges...", n, streetEdges.size());
            }
        }
        LOG.info("Set speed on {} edges, {} no speed, {} clamped", nSet, nNoSpeed, nClampedSpeed);

        long end = System.currentTimeMillis();
        LOG.info("Car speed processing took " + (end - start) + "ms");
    }

    @Override
    public void checkInputs() {
        if (!shapefile.canRead())
            throw new RuntimeException("Can't read car speed shapefile: " + shapefile);
    }
}
