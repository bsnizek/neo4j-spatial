/*
 * Copyright (c) 2010
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gis.spatial;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ReturnableEvaluator;
import org.neo4j.graphdb.StopEvaluator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Traverser.Order;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;


/**
 * @author Davide Savazzi
 */
public class SpatialDatabaseService implements Constants {

    private Node spatialRoot;


    // Constructor
	
	public SpatialDatabaseService(GraphDatabaseService database) {
		this.database = database;
	}

	
	// Public methods

    private Node getOrCreateRootFrom(Node ref, RelationshipType relType) {
        Relationship rel = ref.getSingleRelationship(relType, Direction.OUTGOING);
        if (rel == null) {
            Transaction tx = database.beginTx();
            try {
                Node node = database.createNode();
                node.setProperty("type", "spatial");
                ref.createRelationshipTo(node, relType);
                tx.success();
                return node;
            } finally {
                tx.finish();
            }
        } else {
            return rel.getEndNode();
        }
    }

    protected Node getSpatialRoot() {
        if (spatialRoot == null) {
            spatialRoot = getOrCreateRootFrom(database.getReferenceNode(), SpatialRelationshipTypes.SPATIAL);
        }
        return spatialRoot;
    }

	public String[] getLayerNames() {
		List<String> names = new ArrayList<String>();
		
		for (Relationship relationship : getSpatialRoot().getRelationships(SpatialRelationshipTypes.LAYER, Direction.OUTGOING)) {
            Layer layer = DefaultLayer.makeLayerFromNode(this, relationship.getEndNode());
            if (layer instanceof DynamicLayer) {
            	names.addAll(((DynamicLayer)layer).getLayerNames());
            } else {
            	names.add(layer.getName());
            }
        }
        
		return names.toArray(new String[names.size()]);
	}
	
	public Layer getLayer(String name) {
		ArrayList<DynamicLayer> dynamicLayers = new ArrayList<DynamicLayer>();
		for (Relationship relationship : getSpatialRoot().getRelationships(SpatialRelationshipTypes.LAYER, Direction.OUTGOING)) {
			Node node = relationship.getEndNode();
			if (name.equals(node.getProperty(PROP_LAYER))) {
				return DefaultLayer.makeLayerFromNode(this, node);
			}
			if (!node.getProperty(PROP_LAYER_CLASS, "").toString().startsWith("DefaultLayer")) {
				Layer layer = DefaultLayer.makeLayerFromNode(this, node);
				if (layer instanceof DynamicLayer) {
					dynamicLayers.add((DynamicLayer) DefaultLayer.makeLayerFromNode(this, node));
				}
			}
		}
		for (DynamicLayer layer : dynamicLayers) {
			for (String dynLayerName : layer.getLayerNames()) {
				if (name.equals(dynLayerName)) {
					return layer.getLayer(dynLayerName);
				}
			}
		}
		return null;
	}

    public DefaultLayer getOrCreateDefaultLayer(String name) {
        return (DefaultLayer)getOrCreateLayer(name, WKBGeometryEncoder.class, DefaultLayer.class);
    }

    public EditableLayer getOrCreateEditableLayer(String name) {
        return (EditableLayer)getOrCreateLayer(name, WKBGeometryEncoder.class, EditableLayerImpl.class);
    }

    public Layer getOrCreateLayer(String name, Class< ? extends GeometryEncoder> geometryEncoder, Class< ? extends Layer> layerClass) {
        Layer layer = getLayer(name);
        if (layer == null) {
            return createLayer(name, geometryEncoder, layerClass);
        } else if(layerClass == null || layerClass.isInstance(layer)) {
        	return layer;
        } else {
        	throw new SpatialDatabaseException("Existing layer '"+layer+"' is not of the expected type: "+layerClass);
        }
    }

    /**
     * This method will find the Layer when given a geometry node that this layer contains. It first
     * searches up the RTree index if it exists, and if it cannot find the layer node, it searches
     * back the NEXT_GEOM chain. This is the structure created by the default implementation of the
     * Layer class, so we should consider moving this to the Layer class, so it can be overridden by
     * other implementations that do not use that structure.
     * 
     * @TODO: Find a way to override this as we can override normal Layer with different graph structures.
     * 
     * @param geometryNode to start search
     * @return Layer object containing this geometry
     */
    public Layer findLayerContainingGeometryNode(Node geometryNode) {
        Node root = null;
        for (Node node : geometryNode.traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH,
                ReturnableEvaluator.ALL_BUT_START_NODE, SpatialRelationshipTypes.RTREE_REFERENCE, Direction.INCOMING,
                SpatialRelationshipTypes.RTREE_CHILD, Direction.INCOMING)) {
            root = node;
        }
        if (root != null) {
            return getLayerFromChild(root, SpatialRelationshipTypes.RTREE_ROOT);
        }
        System.out.println("Failed to find layer by following RTree index, will search back geometry list");
        for (Node node : geometryNode.traverse(Order.DEPTH_FIRST, StopEvaluator.END_OF_GRAPH,
                ReturnableEvaluator.ALL_BUT_START_NODE, SpatialRelationshipTypes.NEXT_GEOM, Direction.INCOMING)) {
            root = node;
        }
        if (root != null) {
            return getLayerFromChild(root, SpatialRelationshipTypes.NEXT_GEOM);
        }
        return null;
    }

    private Layer getLayerFromChild(Node child, RelationshipType relType) {
        Relationship indexRel = child.getSingleRelationship(relType, Direction.INCOMING);
        if (indexRel != null) {
            Node layerNode = indexRel.getStartNode();
            if (layerNode.hasProperty(PROP_LAYER)) {
                return DefaultLayer.makeLayerFromNode(this, layerNode);
            }
        }
        return null;
    }
	
	public boolean containsLayer(String name) {
		return getLayer(name) != null;
	}
	
    public Layer createLayer(String name) {
        return createLayer(name, WKBGeometryEncoder.class, DefaultLayer.class);
    }

    public Layer createLayer(String name, Class<? extends GeometryEncoder> geometryEncoderClass, Class<? extends Layer> layerClass) {
        Transaction tx = database.beginTx();
        try {
            if (containsLayer(name))
                throw new SpatialDatabaseException("Layer " + name + " already exists");

            Layer layer = DefaultLayer.makeLayerAndNode(this, name, geometryEncoderClass, layerClass);
            getSpatialRoot().createRelationshipTo(layer.getLayerNode(), SpatialRelationshipTypes.LAYER);
            tx.success();
            return layer;
        } finally {
            tx.finish();
        }
	}

    public void deleteLayer(String name, Listener monitor) {
        Layer layer = getLayer(name);
        if (layer == null)
            throw new SpatialDatabaseException("Layer " + name + " does not exist");

        layer.delete(monitor);
    }
	
	public GraphDatabaseService getDatabase() {
		return database;
	}
	
	
	// Attributes
	
	private GraphDatabaseService database;

	public static String convertGeometryTypeToName(Integer geometryType) {
		return convertGeometryTypeToJtsClass(geometryType).getName().replace("com.vividsolutions.jts.geom.", "");
	}

	public static Class<? extends Geometry> convertGeometryTypeToJtsClass(Integer geometryType) {
		switch (geometryType) {
			case GTYPE_POINT: return Point.class;
			case GTYPE_LINESTRING: return LineString.class; 
			case GTYPE_POLYGON: return Polygon.class;
			case GTYPE_MULTIPOINT: return MultiPoint.class;
			case GTYPE_MULTILINESTRING: return MultiLineString.class;
			case GTYPE_MULTIPOLYGON: return MultiPolygon.class;
			default: return Geometry.class;
		}
	}

	public static int convertJtsClassToGeometryType(Class<? extends Geometry> jtsClass) {
		if (jtsClass.equals(Point.class)) {
			return GTYPE_POINT;
		} else if (jtsClass.equals(LineString.class)) {
			return GTYPE_LINESTRING;
		} else if (jtsClass.equals(Polygon.class)) {
			return GTYPE_POLYGON;
		} else if (jtsClass.equals(MultiPoint.class)) {
			return GTYPE_MULTIPOINT;
		} else if (jtsClass.equals(MultiLineString.class)) {
			return GTYPE_MULTILINESTRING;
		} else if (jtsClass.equals(MultiPolygon.class)) {
			return GTYPE_MULTIPOLYGON;
		} else {
			return GTYPE_GEOMETRY;
		}
	}

}