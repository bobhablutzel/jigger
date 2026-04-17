/*
 * Copyright 2026 Bob Hablutzel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Source: https://github.com/bobhablutzel/jigger
 */

package com.jigger;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.control.BillboardControl;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Sphere;

import com.jigger.model.Assembly;
import com.jigger.model.GuillotinePacker;
import com.jigger.model.Joint;
import com.jigger.model.JointRegistry;
import com.jigger.model.Part;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jME3 application that manages the 3D scene.
 * Renders axes and user-created objects.
 *
 * For boxes, the user-facing "position" is the minimum corner (origin corner).
 * The box extends in the positive x/y/z direction from that corner.
 * For spheres and cylinders, position is the center.
 */
public class SceneManager extends SimpleApplication {

    private final Node objectsNode = new Node("objects");
    private final Map<String, Node> objectNodes = new ConcurrentHashMap<>();  // wrapper nodes at corner position
    private final Map<String, Geometry> geometries = new ConcurrentHashMap<>();
    private final Map<String, ObjectRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Node> labelNodes = new ConcurrentHashMap<>();
    private final Map<String, Part> parts = new ConcurrentHashMap<>();
    private final Map<String, Assembly> assemblies = new ConcurrentHashMap<>();
    private final JointRegistry jointRegistry = new JointRegistry();
    private final Map<String, Vector3f> rotations = new ConcurrentHashMap<>();  // degrees (x,y,z)
    private float kerfMm = GuillotinePacker.DEFAULT_KERF_MM;
    private volatile boolean cutSheetDirty = true;
    private final List<Runnable> sceneChangeListeners = new ArrayList<>();
    private CameraController cameraController;
    private BitmapFont labelFont;

    /** Immutable record of the parameters used to create an object. */
    public record ObjectRecord(String name, String shapeType, Vector3f position,
                               Vector3f size, ColorRGBA color) {}

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);

        cameraController = new CameraController(cam, inputManager);
        stateManager.attach(cameraController);

        rootNode.attachChild(AxisDisplay.create(assetManager));
        rootNode.attachChild(objectsNode);
        labelFont = assetManager.loadFont("Interface/Fonts/Default.fnt");

        AmbientLight ambient = new AmbientLight();
        ambient.setColor(ColorRGBA.White.mult(0.3f));
        rootNode.addLight(ambient);

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(0.7f));
        rootNode.addLight(sun);

        cam.setFrustumPerspective(45f, (float) cam.getWidth() / cam.getHeight(), 1f, 100000f);
        cam.setLocation(new Vector3f(1500, 1200, 1500));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

        viewPort.setBackgroundColor(new ColorRGBA(0.15f, 0.15f, 0.18f, 1f));
    }

    public String createObject(String name, String shapeType, Vector3f position, Vector3f size, ColorRGBA color) {
        records.put(name, new ObjectRecord(name, shapeType, position, size, color));
        Vector3f geomOffset = geomOffsetInNode(shapeType, size);
        enqueue(() -> {
            Geometry geom = buildGeometry(name, shapeType, size);
            Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            mat.setBoolean("UseMaterialColors", true);
            mat.setColor("Diffuse", color);
            mat.setColor("Ambient", color.mult(0.5f));
            geom.setMaterial(mat);
            geom.setLocalTranslation(geomOffset);

            Node wrapper = new Node("node_" + name);
            wrapper.attachChild(geom);
            wrapper.setLocalTranslation(position);
            objectsNode.attachChild(wrapper);
            objectNodes.put(name, wrapper);
            geometries.put(name, geom);
        });
        return name;
    }

    /**
     * Create a part from the woodworking domain model.
     * The part's material determines thickness and display color.
     */
    public String createPart(Part part) {
        String name = part.getName();
        Vector3f size = part.toSizeVector();
        Vector3f position = part.getPosition() != null ? part.getPosition() : Vector3f.ZERO;
        ColorRGBA color = part.getMaterial().getDisplayColor();

        parts.put(name, part);
        markCutSheetDirty();
        records.put(name, new ObjectRecord(name, "box", position, size, color));

        Vector3f geomOffset = geomOffsetInNode("box", size);
        enqueue(() -> {
            Geometry geom = buildGeometry(name, "box", size);
            Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            mat.setBoolean("UseMaterialColors", true);
            mat.setColor("Diffuse", color);
            mat.setColor("Ambient", color.mult(0.5f));
            geom.setMaterial(mat);
            geom.setLocalTranslation(geomOffset);

            Node wrapper = new Node("node_" + name);
            wrapper.attachChild(geom);
            wrapper.setLocalTranslation(position);
            objectsNode.attachChild(wrapper);
            objectNodes.put(name, wrapper);
            geometries.put(name, geom);
        });
        return name;
    }

    public Part getPart(String name) {
        return parts.get(name);
    }

    public Map<String, Part> getAllParts() {
        return Map.copyOf(parts);
    }

    public void registerAssembly(Assembly assembly) {
        assemblies.put(assembly.getName(), assembly);
    }

    public Assembly getAssembly(String name) {
        return assemblies.get(name);
    }

    public void removeAssembly(String name) {
        assemblies.remove(name);
    }

    public Map<String, Assembly> getAllAssemblies() {
        return Map.copyOf(assemblies);
    }

    public JointRegistry getJointRegistry() {
        return jointRegistry;
    }

    /**
     * Compute the world-space axis-aligned bounding box [min, max] for an object,
     * accounting for rotation. Uses pure math from the ObjectRecord + stored rotation,
     * independent of the render thread.
     */
    public Vector3f[] computeObjectAABB(String name) {
        ObjectRecord rec = records.get(name);
        if (rec == null) return null;

        Vector3f pos = rec.position();
        Vector3f size = rec.size();
        Vector3f rotDeg = getRotation(name);

        if (rotDeg.equals(Vector3f.ZERO)) {
            // No rotation — simple case
            return new Vector3f[]{pos.clone(), pos.add(size)};
        }

        // Compute AABB of the rotated box.
        // The geometry center is at pos + halfSize (because of the geomOffset in the wrapper node).
        // The wrapper node is rotated around its origin (pos), so we need to rotate the
        // center offset and the half-extents.
        Quaternion q = new Quaternion().fromAngles(
                rotDeg.x * FastMath.DEG_TO_RAD,
                rotDeg.y * FastMath.DEG_TO_RAD,
                rotDeg.z * FastMath.DEG_TO_RAD);

        Vector3f halfSize = new Vector3f(size.x / 2f, size.y / 2f, size.z / 2f);
        Vector3f rotatedCenter = q.mult(halfSize).addLocal(pos);

        // AABB extents of a rotated box: project each rotated axis onto world axes
        com.jme3.math.Matrix3f rot = q.toRotationMatrix();
        float ex = Math.abs(rot.get(0, 0)) * halfSize.x + Math.abs(rot.get(0, 1)) * halfSize.y + Math.abs(rot.get(0, 2)) * halfSize.z;
        float ey = Math.abs(rot.get(1, 0)) * halfSize.x + Math.abs(rot.get(1, 1)) * halfSize.y + Math.abs(rot.get(1, 2)) * halfSize.z;
        float ez = Math.abs(rot.get(2, 0)) * halfSize.x + Math.abs(rot.get(2, 1)) * halfSize.y + Math.abs(rot.get(2, 2)) * halfSize.z;

        return new Vector3f[]{
                new Vector3f(rotatedCenter.x - ex, rotatedCenter.y - ey, rotatedCenter.z - ez),
                new Vector3f(rotatedCenter.x + ex, rotatedCenter.y + ey, rotatedCenter.z + ez)
        };
    }

    public float getKerfMm() {
        return kerfMm;
    }

    public void setKerfMm(float kerfMm) {
        this.kerfMm = kerfMm;
        markCutSheetDirty();
    }

    /**
     * Mark the cut sheet as needing recomputation.
     * Call this after modifying joints or other data that affects cut layouts.
     */
    public void markCutSheetDirty() {
        cutSheetDirty = true;
        for (Runnable listener : sceneChangeListeners) {
            listener.run();
        }
    }

    /**
     * Register a listener to be notified when the scene changes in a way
     * that affects cut sheets. Used by CutSheetPanel to trigger repaint.
     */
    public void addSceneChangeListener(Runnable listener) {
        sceneChangeListeners.add(listener);
    }

    /**
     * Returns true if parts/joints have changed since the last call to
     * {@link #clearCutSheetDirty()}.
     */
    public boolean isCutSheetDirty() {
        return cutSheetDirty;
    }

    /**
     * Clear the dirty flag after a cut sheet recompute.
     */
    public void clearCutSheetDirty() {
        cutSheetDirty = false;
    }

    private boolean statsVisible = false;

    public boolean toggleStats() {
        statsVisible = !statsVisible;
        enqueue(() -> {
            setDisplayStatView(statsVisible);
            setDisplayFps(statsVisible);
        });
        return statsVisible;
    }

    /** Get the wrapper node's world translation (for debugging). */
    public Vector3f getNodeWorldTranslation(String name) {
        Node wrapper = objectNodes.get(name);
        return wrapper != null ? wrapper.getWorldTranslation() : null;
    }

    /** Get the geometry's local translation within its wrapper (for debugging). */
    public Vector3f getGeomLocalTranslation(String name) {
        Geometry geom = geometries.get(name);
        return geom != null ? geom.getLocalTranslation() : null;
    }

    /** Get the geometry's world-space bounding box min/max (for debugging). */
    public Vector3f[] getWorldBounds(String name) {
        Geometry geom = geometries.get(name);
        if (geom == null) return null;
        var bb = geom.getWorldBound();
        if (bb instanceof com.jme3.bounding.BoundingBox box) {
            Vector3f min = box.getCenter().subtract(box.getXExtent(), box.getYExtent(), box.getZExtent());
            Vector3f max = box.getCenter().add(box.getXExtent(), box.getYExtent(), box.getZExtent());
            return new Vector3f[]{min, max};
        }
        return null;
    }

    public boolean moveObject(String name, Vector3f newPosition) {
        ObjectRecord rec = records.get(name);
        if (rec == null) return false;
        records.put(name, new ObjectRecord(name, rec.shapeType(), newPosition, rec.size(), rec.color()));
        enqueue(() -> {
            Node wrapper = objectNodes.get(name);
            if (wrapper != null) {
                wrapper.setLocalTranslation(newPosition);
            }
        });
        refreshLabels(name);
        return true;
    }

    public boolean resizeObject(String name, Vector3f newSize) {
        ObjectRecord rec = records.get(name);
        if (rec == null) return false;
        if (parts.containsKey(name)) markCutSheetDirty();
        records.put(name, new ObjectRecord(name, rec.shapeType(), rec.position(), newSize, rec.color()));
        Vector3f geomOffset = geomOffsetInNode(rec.shapeType(), newSize);
        enqueue(() -> {
            Node wrapper = objectNodes.get(name);
            Geometry old = geometries.get(name);
            if (wrapper != null && old != null) {
                Geometry replacement = buildGeometry(name, rec.shapeType(), newSize);
                replacement.setMaterial(old.getMaterial());
                replacement.setLocalTranslation(geomOffset);
                wrapper.detachChild(old);
                wrapper.attachChild(replacement);
                geometries.put(name, replacement);
            }
        });
        refreshLabels(name);
        return true;
    }

    /**
     * Set the rotation of an object in degrees around X, Y, Z axes.
     * Rotation is absolute (not additive).
     */
    public boolean rotateObject(String name, Vector3f degrees) {
        ObjectRecord rec = records.get(name);
        if (rec == null) return false;
        rotations.put(name, degrees.clone());
        Quaternion quat = new Quaternion().fromAngles(
                degrees.x * FastMath.DEG_TO_RAD,
                degrees.y * FastMath.DEG_TO_RAD,
                degrees.z * FastMath.DEG_TO_RAD);
        enqueue(() -> {
            Node wrapper = objectNodes.get(name);
            if (wrapper != null) {
                wrapper.setLocalRotation(quat);
            }
        });
        refreshLabels(name);
        return true;
    }

    /** Get current rotation in degrees, or (0,0,0) if never rotated. */
    public Vector3f getRotation(String name) {
        return rotations.getOrDefault(name, Vector3f.ZERO);
    }

    private static final float LABEL_FONT_SIZE = 30f;  // fixed size in mm, readable at workshop scale

    public boolean isNameDisplayed(String name) {
        return labelNodes.containsKey(name);
    }

    public boolean displayName(String name) {
        ObjectRecord rec = records.get(name);
        if (rec == null) return false;
        if (labelNodes.containsKey(name)) return true;
        enqueue(() -> {
            Node wrapper = objectNodes.get(name);
            if (wrapper != null) {
                Node labels = buildLabels(rec);
                wrapper.attachChild(labels);
                labelNodes.put(name, labels);
            }
        });
        return true;
    }

    public boolean hideName(String name) {
        if (!labelNodes.containsKey(name)) return false;
        enqueue(() -> {
            Node labels = labelNodes.remove(name);
            if (labels != null) {
                labels.removeFromParent();
            }
        });
        return true;
    }

    /** Show names on all objects. Returns the count displayed. */
    public int displayAllNames() {
        int count = 0;
        for (String name : records.keySet()) {
            if (!labelNodes.containsKey(name)) {
                displayName(name);
                count++;
            }
        }
        return count;
    }

    /** Hide names on all objects. Returns the count hidden. */
    public int hideAllNames() {
        int count = labelNodes.size();
        for (String name : List.copyOf(labelNodes.keySet())) {
            hideName(name);
        }
        return count;
    }

    /** Rebuild labels after a resize if they're currently displayed. */
    private void refreshLabels(String name) {
        if (labelNodes.containsKey(name)) {
            enqueue(() -> {
                Node old = labelNodes.remove(name);
                if (old != null) old.removeFromParent();
                ObjectRecord rec = records.get(name);
                Node wrapper = objectNodes.get(name);
                if (rec != null && wrapper != null) {
                    Node labels = buildLabels(rec);
                    wrapper.attachChild(labels);
                    labelNodes.put(name, labels);
                }
            });
        }
    }

    /**
     * Build face labels positioned relative to the geometry inside the wrapper node.
     * Labels are children of the wrapper, so they rotate/move with the object automatically.
     */
    private Node buildLabels(ObjectRecord rec) {
        String text = rec.name();
        Vector3f size = rec.size();
        float hx = size.x / 2f, hy = size.y / 2f, hz = size.z / 2f;
        // geomCenter is the geometry offset inside the wrapper (= half-size for boxes)
        Vector3f geomCenter = geomOffsetInNode(rec.shapeType(), size);
        Node labelGroup = new Node("labels_" + rec.name());

        if ("box".equals(rec.shapeType().toLowerCase())) {
            float offset = 0.5f; // slight offset from face to avoid z-fighting

            // Front (+Z), Back (-Z)
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(0, 0, hz + offset), new Quaternion().fromAngles(0, 0, 0));
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(0, 0, -(hz + offset)), new Quaternion().fromAngles(0, FastMath.PI, 0));

            // Right (+X), Left (-X)
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(hx + offset, 0, 0), new Quaternion().fromAngles(0, FastMath.HALF_PI, 0));
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(-(hx + offset), 0, 0), new Quaternion().fromAngles(0, -FastMath.HALF_PI, 0));

            // Top (+Y), Bottom (-Y)
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(0, hy + offset, 0), new Quaternion().fromAngles(-FastMath.HALF_PI, 0, 0));
            addFaceLabel(labelGroup, text, geomCenter,
                    new Vector3f(0, -(hy + offset), 0), new Quaternion().fromAngles(FastMath.HALF_PI, 0, 0));
        } else {
            // Billboard label above the object
            BitmapText bt = new BitmapText(labelFont);
            bt.setText(text);
            bt.setSize(LABEL_FONT_SIZE);
            bt.setColor(ColorRGBA.White);
            bt.setQueueBucket(RenderQueue.Bucket.Transparent);

            Node wrapper = new Node("billboard_label");
            wrapper.attachChild(bt);
            bt.setLocalTranslation(-bt.getLineWidth() / 2f, 0, 0);
            wrapper.setLocalTranslation(geomCenter.add(0, size.x + 20f, 0));
            wrapper.addControl(new BillboardControl());
            labelGroup.attachChild(wrapper);
        }

        return labelGroup;
    }

    private void addFaceLabel(Node parent, String text, Vector3f geomCenter,
                              Vector3f faceOffset, Quaternion rotation) {
        BitmapText bt = new BitmapText(labelFont);
        bt.setText(text);
        bt.setSize(LABEL_FONT_SIZE);
        bt.setColor(ColorRGBA.White);
        bt.setQueueBucket(RenderQueue.Bucket.Transparent);

        Node wrapper = new Node("face_label");
        wrapper.attachChild(bt);
        // Center the text (BitmapText origin is top-left)
        bt.setLocalTranslation(-bt.getLineWidth() / 2f, bt.getLineHeight() / 2f, 0);

        wrapper.setLocalTranslation(geomCenter.add(faceOffset));
        wrapper.setLocalRotation(rotation);
        parent.attachChild(wrapper);
    }

    public boolean deleteObject(String id) {
        ObjectRecord rec = records.remove(id);
        if (parts.remove(id) != null) markCutSheetDirty();
        rotations.remove(id);
        jointRegistry.removeJointsForPart(id);
        geometries.remove(id);
        Node wrapper = objectNodes.remove(id);
        hideName(id);
        if (wrapper != null) {
            enqueue(() -> wrapper.removeFromParent());
        } else if (rec != null) {
            // The create enqueue may not have run yet — schedule deferred cleanup.
            // This runs after the pending create because enqueue is FIFO.
            enqueue(() -> {
                Node w = objectNodes.remove(id);
                if (w != null) w.removeFromParent();
                geometries.remove(id);
            });
        }
        return rec != null;
    }

    public void deleteAllObjects() {
        markCutSheetDirty();
        enqueue(() -> {
            objectsNode.detachAllChildren();
            objectNodes.clear();
            geometries.clear();
            records.clear();
            labelNodes.clear();
            parts.clear();
            assemblies.clear();
            jointRegistry.clear();
            rotations.clear();
        });
    }

    /** Returns a snapshot of all object records (for undo of delete-all). */
    public Map<String, ObjectRecord> getObjectRecords() {
        return new LinkedHashMap<>(records);
    }

    public ObjectRecord getObjectRecord(String name) {
        return records.get(name);
    }

    public Map<String, Geometry> getObjects() {
        return Map.copyOf(geometries);
    }

    /** Get the node containing all user-created objects (for ray-cast picking). */
    public Node getObjectsNode() {
        return objectsNode;
    }

    /** Connect the selection manager to the camera controller for mouse picking. */
    public void setSelectionManager(SelectionManager selectionManager) {
        enqueue(() -> {
            if (cameraController != null) {
                cameraController.setSelectionManager(selectionManager, objectsNode);
            }
        });
    }

    private static final ColorRGBA HIGHLIGHT_TINT = new ColorRGBA(0.3f, 0.6f, 1.0f, 1f);

    /** Highlight an object by tinting it. Call from render thread. */
    public void setHighlight(String name, boolean highlighted) {
        enqueue(() -> {
            Geometry geom = geometries.get(name);
            if (geom == null) return;
            Material mat = geom.getMaterial();
            if (highlighted) {
                // Store original colors as user data for restoration
                ColorRGBA origDiffuse = (ColorRGBA) mat.getParam("Diffuse").getValue();
                ColorRGBA origAmbient = (ColorRGBA) mat.getParam("Ambient").getValue();
                geom.setUserData("origDiffuse", new float[]{origDiffuse.r, origDiffuse.g, origDiffuse.b, origDiffuse.a});
                geom.setUserData("origAmbient", new float[]{origAmbient.r, origAmbient.g, origAmbient.b, origAmbient.a});
                // Tint toward highlight color
                mat.setColor("Diffuse", origDiffuse.clone().interpolateLocal(HIGHLIGHT_TINT, 0.4f));
                mat.setColor("Ambient", origAmbient.clone().interpolateLocal(HIGHLIGHT_TINT, 0.4f));
                // Add wireframe overlay
                mat.getAdditionalRenderState().setWireframe(true);
            } else {
                // Restore original colors
                float[] od = (float[]) geom.getUserData("origDiffuse");
                float[] oa = (float[]) geom.getUserData("origAmbient");
                if (od != null) mat.setColor("Diffuse", new ColorRGBA(od[0], od[1], od[2], od[3]));
                if (oa != null) mat.setColor("Ambient", new ColorRGBA(oa[0], oa[1], oa[2], oa[3]));
                mat.getAdditionalRenderState().setWireframe(false);
            }
        });
    }

    /**
     * Geometry offset inside its wrapper node.
     * For boxes, the node origin is at the min corner, so the geometry
     * (centered at its local origin) must be shifted by +halfSize.
     * For spheres/cylinders, the node origin is the center — no offset.
     */
    private Vector3f geomOffsetInNode(String shapeType, Vector3f size) {
        if ("box".equals(shapeType.toLowerCase())) {
            return new Vector3f(size.x / 2f, size.y / 2f, size.z / 2f);
        }
        return Vector3f.ZERO;
    }

    /**
     * Build jME3 geometry. User-facing size values are full dimensions
     * (width, height, depth). jME3's Box takes half-extents, so we halve them.
     */
    private Geometry buildGeometry(String name, String shapeType, Vector3f size) {
        return switch (shapeType.toLowerCase()) {
            case "sphere" -> new Geometry(name, new Sphere(32, 32, size.x));
            case "cylinder" -> new Geometry(name, new Cylinder(32, 32, size.x, size.y, true));
            default -> new Geometry(name, new Box(size.x / 2f, size.y / 2f, size.z / 2f));
        };
    }
}
