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

package org.opentripplanner.routing.carspeed;

import java.util.List;

import org.opentripplanner.common.geometry.GeometryUtils;
import org.opentripplanner.common.geometry.HashGridSpatialIndex;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.linearref.LengthIndexedLine;

/**
 * A vector field of actual/estimated car speed at a given instant of time.
 * 
 * @author laurent
 */
public class CarSpeedVectorField {

    private static final Logger LOG = LoggerFactory.getLogger(CarSpeedVectorField.class);

    private double cosLat;

    private double radiusMeters;

    private static final double RADIUS_MULT = 3.0;

    private static final double W0 = 0.1;

    private SpatialIndex spatialIndex;

    public CarSpeedVectorField(double cosLat, double radiusMeters) {
        this.cosLat = cosLat;
        this.radiusMeters = radiusMeters;
        spatialIndex = new HashGridSpatialIndex<>(radiusMeters * 5, radiusMeters * 5);
    }

    /**
     * Add an actual speed for a given geometry.
     * 
     * @param geom The geometry, in WGS84 (EPSG:4326) CRS.
     * @param speed The speed for this geometry.
     * @param fwd True if the geometry is to be processed in the forward direction.
     * @param rev True if the geometry is to be processed in the backward direction.
     */
    public void addGeometry(Geometry geom, float speed, boolean fwd, boolean rev) {
        if (geom instanceof LineString) {
            addGeometry((LineString) geom, speed, fwd, rev);
        } else if (geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                addGeometry(geom.getGeometryN(i), speed, fwd, rev);
            }
        } else {
            LOG.warn("Can't process geometry of type {}", geom.getClass().getName());
        }
    }

    /**
     * See addGeometry.
     * 
     * @param ls
     * @param speed
     * @param fwd
     * @param rev
     */
    public void addGeometry(LineString ls, float speed, boolean fwd, boolean rev) {
        LineString pls = project(ls);
        LengthIndexedLine lipls = new LengthIndexedLine(pls);
        double start = lipls.getStartIndex();
        double end = lipls.getEndIndex();
        int nSteps = (int) Math.round((end - start) / (radiusMeters / 3));
        if (nSteps == 0)
            nSteps = 1;
        double xStep = (end - start) / nSteps * 0.999999;
        double x = start;
        Coordinate a = null;
        while (x <= end) {
            Coordinate b = lipls.extractPoint(x);
            if (a != null) {
                Coordinate m = new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2);
                Envelope env = new Envelope(m);
                Coordinate u = new Coordinate(b.x - a.x, b.y - a.y);
                double uLen = u.distance(new Coordinate(0, 0));
                u.x /= uLen;
                u.y /= uLen;
                if (fwd) {
                    SpeedVector speedVec = new SpeedVector();
                    speedVec.location = m;
                    speedVec.speed = speed;
                    speedVec.direction = u;
                    spatialIndex.insert(env, speedVec);
                }
                if (rev) {
                    SpeedVector speedVec = new SpeedVector();
                    speedVec.location = m;
                    speedVec.speed = speed;
                    speedVec.direction = new Coordinate(-u.x, -u.y);
                    spatialIndex.insert(env, speedVec);
                }
            }
            a = b;
            x += xStep;
        }
    }

    /**
     * Estimate (interpolate) the speed for a given edge.
     * 
     * @param e The edge to estimate speed for.
     * @return The estimated speed, null if no estimated speed can be found.
     */
    public Float interpolateSpeedForEdge(StreetEdge e) {
        LineString ls = e.getGeometry();
        LineString pls = project(ls);

        // Query vector points for the edge
        Envelope env = pls.getEnvelopeInternal();
        env.expandBy(radiusMeters * RADIUS_MULT);
        @SuppressWarnings("unchecked")
        List<SpeedVector> vectors = spatialIndex.query(env);
        if (vectors.isEmpty())
            return null; // No data here

        // Integrate vector field alongside edge path
        // given interpolated vector field
        LengthIndexedLine lipls = new LengthIndexedLine(pls);
        double start = lipls.getStartIndex();
        double end = lipls.getEndIndex();
        int nSteps = (int) Math.round((end - start) / (radiusMeters / 3));
        if (nSteps == 0)
            nSteps = 1;
        double xStep = (end - start) / nSteps * 0.999999;
        double x = start;
        double wSpeed = 0.0, wSum = 0.0;
        Coordinate a = null;
        while (x <= end) {
            Coordinate b = lipls.extractPoint(x);
            if (a != null) {
                Coordinate m = new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2);
                Coordinate u = new Coordinate(b.x - a.x, b.y - a.y);
                double uLen = u.distance(new Coordinate(0, 0));
                u.x /= uLen;
                u.y /= uLen;
                for (SpeedVector sp : vectors) {
                    double w = weight(sp.location, m, sp.direction, u);
                    wSpeed += w * sp.speed;
                    wSum += w;
                }
                wSpeed += W0 * e.getCarSpeed();
                wSum += W0;
            }
            a = b;
            x += xStep;
        }
        return (float) (wSpeed / wSum);
    }

    /**
     * Compute the weighting of an actual sampled point for a given position / direction.
     * 
     * @param a The first point position (sampled value)
     * @param b The second point position (position to weight)
     * @param u The unit vector direction of the first point speed
     * @param v The unit vector direction of the second point speed
     * @return A unit-less weight between 1 and 0.
     */
    private final double weight(Coordinate a, Coordinate b, Coordinate u, Coordinate v) {
        // Compute the dot-product of the two direction vector
        double dp = u.x * v.x + u.y * v.y;
        // Wrong direction: weight = 0
        if (dp <= 0)
            return 0;
        // Compute the distance between the points
        // The result is directly in meters, as we equirectangular-project using meters coordinates
        double d = a.distance(b);
        if (d > radiusMeters * RADIUS_MULT)
            return 0.0; // Too far away
        if (d < radiusMeters)
            return 1.0 * dp; // Close points
        // Linear interpolation between radius: dp and radius * radius_mult: 0
        return ((radiusMeters * RADIUS_MULT - d) / (radiusMeters * (RADIUS_MULT - 1))) * dp;
    }

    private LineString project(LineString lineString) {
        // TODO Use a MathTransform or something similar?
        Coordinate[] coords = lineString.getCoordinates();
        Coordinate[] coords2 = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            coords2[i] = new Coordinate(Math.toRadians(coords[i].x) * cosLat
                    * SphericalDistanceLibrary.RADIUS_OF_EARTH_IN_M, Math.toRadians(coords[i].y)
                    * SphericalDistanceLibrary.RADIUS_OF_EARTH_IN_M);
        }
        return GeometryUtils.getGeometryFactory().createLineString(coords2);
    }

    private static class SpeedVector {
        // TODO We could add street class and name for better matching

        // Location of speed data
        private Coordinate location;

        // Unit vector of direction
        private Coordinate direction;

        // Actual speed (magnitude)
        private float speed;
    }
}
