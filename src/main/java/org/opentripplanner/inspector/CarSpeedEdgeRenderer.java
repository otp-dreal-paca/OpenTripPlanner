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

package org.opentripplanner.inspector;

import org.opentripplanner.inspector.EdgeVertexTileRenderer.EdgeVertexRenderer;
import org.opentripplanner.inspector.EdgeVertexTileRenderer.EdgeVisualAttributes;
import org.opentripplanner.inspector.EdgeVertexTileRenderer.VertexVisualAttributes;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Vertex;

/**
 * Render car speed for each edge using a color palette and a label.
 * 
 * @author laurent
 * 
 */
public class CarSpeedEdgeRenderer implements EdgeVertexRenderer {

    private ScalarColorPalette palette = new DefaultScalarColorPalette(0, 20.0, 36.0);

    public CarSpeedEdgeRenderer() {
    }

    @Override
    public boolean renderEdge(Edge e, EdgeVisualAttributes attrs) {
        if (e instanceof StreetEdge) {
            StreetEdge pse = (StreetEdge) e;
            if (pse.getPermission().allows(TraverseMode.CAR)) {
                float carSpeed = pse.getCarSpeed();
                attrs.color = palette.getColor(carSpeed);
                attrs.label = String.format("%.01f km/h", carSpeed * 3.6);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public boolean renderVertex(Vertex v, VertexVisualAttributes attrs) {
        return false;
    }

    @Override
    public String getName() {
        return "Car speed";
    }
}