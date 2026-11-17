import hashlib
import pathlib
import tempfile
import unittest
import verify_consumer_origins as origins

class ConsumerOriginTest(unittest.TestCase):
    def test_actual_loaded_jar_is_hashed_against_current_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            path=pathlib.Path(directory)/'core.jar';path.write_bytes(b'current')
            manifest={'dialogue-core':{'sha256':hashlib.sha256(b'current').hexdigest()}}
            log='MAVEN_ORIGIN gg.grounds.dialogue.core.runtime.DialogueRuntime '+path.as_uri()
            self.assertEqual({'dialogue-core'},origins.verify_log(log,manifest))
            path.write_bytes(b'stale')
            with self.assertRaises(ValueError):origins.verify_log(log,manifest)
    def test_native_consumer_hashes_are_not_silently_skipped(self):
        expected='a'*64
        self.assertEqual({'dialogue-minestom'},origins.verify_log('CONSUMER_JAR dialogue-minestom sha256='+expected,{'dialogue-minestom':{'sha256':expected}}))
        with self.assertRaises(ValueError):origins.verify_log('CONSUMER_JAR dialogue-minestom sha256='+'b'*64,{'dialogue-minestom':{'sha256':expected}})
        with self.assertRaises(ValueError):origins.verify_log('CONSUMER_OK but no origins',{})
