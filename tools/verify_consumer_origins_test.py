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

class CompleteConsumerOriginsTest(unittest.TestCase):
    def test_absentRequiredModuleRejectsEvenIfOtherHashesMatch(self):
        modules={'dialogue-core':{'sha256':'a'*64},'dialogue-testkit':{'sha256':'b'*64}}
        log='CONSUMER_JAR dialogue-core sha256='+'a'*64
        with self.assertRaises(ValueError):origins.verify_logs([log],modules,{'dialogue-testkit'})
    def test_aggregateRequiresEveryRequestedModule(self):
        modules={name:{'sha256':'a'*64} for name in ('dialogue-core','dialogue-testkit','dialogue-cli')}
        logs=['CONSUMER_JAR '+name+' sha256='+'a'*64 for name in modules]
        self.assertEqual(set(modules),origins.verify_logs(logs,modules,set(modules)))
    def test_sourceDirectoryRejects(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):origins.verify_logs(['MAVEN_ORIGIN gg.grounds.dialogue.core.runtime.DialogueRuntime '+pathlib.Path(directory).as_uri()],{'dialogue-core':{'sha256':'a'*64}},{'dialogue-core'})
